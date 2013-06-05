package net.ripe.db.whois.update.authentication;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import net.ripe.db.whois.common.Message;
import net.ripe.db.whois.common.Messages;
import net.ripe.db.whois.common.dao.UserDao;
import net.ripe.db.whois.common.domain.*;
import net.ripe.db.whois.common.profiles.WhoisProfile;
import net.ripe.db.whois.common.rpsl.ObjectType;
import net.ripe.db.whois.common.rpsl.RpslObject;
import net.ripe.db.whois.update.authentication.strategy.AuthenticationFailedException;
import net.ripe.db.whois.update.authentication.strategy.AuthenticationStrategy;
import net.ripe.db.whois.update.domain.*;
import net.ripe.db.whois.update.log.LoggerContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class Authenticator {
    private final IpRanges ipRanges;
    private final UserDao userDao;
    private final LoggerContext loggerContext;
    private final List<AuthenticationStrategy> authenticationStrategies;
    private final Map<CIString, Set<Principal>> principalsMap;
    private final Map<ObjectType, Set<AuthenticationStrategy>> pendingAuthenticationTypes;

    @Autowired
    public Authenticator(final IpRanges ipRanges, final UserDao userDao, final Maintainers maintainers, final LoggerContext loggerContext, final AuthenticationStrategy[] authenticationStrategies) {
        this.ipRanges = ipRanges;
        this.userDao = userDao;
        this.loggerContext = loggerContext;
        this.authenticationStrategies = Lists.newArrayList(authenticationStrategies);

        final Map<CIString, Set<Principal>> tempPrincipalsMap = Maps.newHashMap();
        addMaintainers(tempPrincipalsMap, maintainers.getPowerMaintainers(), Principal.POWER_MAINTAINER);
        addMaintainers(tempPrincipalsMap, maintainers.getEnduserMaintainers(), Principal.ENDUSER_MAINTAINER);
        addMaintainers(tempPrincipalsMap, maintainers.getAllocMaintainers(), Principal.ALLOC_MAINTAINER);
        addMaintainers(tempPrincipalsMap, maintainers.getRsMaintainers(), Principal.RS_MAINTAINER);
        addMaintainers(tempPrincipalsMap, maintainers.getEnumMaintainers(), Principal.ENUM_MAINTAINER);
        addMaintainers(tempPrincipalsMap, maintainers.getDbmMaintainers(), Principal.DBM_MAINTAINER);
        this.principalsMap = Collections.unmodifiableMap(tempPrincipalsMap);

        pendingAuthenticationTypes = Maps.newEnumMap(ObjectType.class);
        for (final AuthenticationStrategy authenticationStrategy : authenticationStrategies) {
            for (final ObjectType objectType : authenticationStrategy.getPendingAuthenticationTypes()) {
                Set<AuthenticationStrategy> strategies = pendingAuthenticationTypes.get(objectType);
                if (strategies == null) {
                    strategies = new HashSet<>();
                    pendingAuthenticationTypes.put(objectType, strategies);
                }

                strategies.add(authenticationStrategy);
            }
        }
    }

    private static void addMaintainers(final Map<CIString, Set<Principal>> principalsMap, final Set<CIString> maintainers, final Principal principal) {
        for (final CIString maintainer : maintainers) {
            Set<Principal> principals = principalsMap.get(maintainer);
            if (principals == null) {
                principals = Sets.newLinkedHashSet();
                principalsMap.put(maintainer, principals);
            }

            principals.add(principal);
        }
    }

    public void authenticate(final Origin origin, final PreparedUpdate update, final UpdateContext updateContext) {
        final Subject subject;

        if (origin.isDefaultOverride()) {
            subject = new Subject(Principal.OVERRIDE_MAINTAINER);
        } else if (update.isOverride()) {
            subject = performOverrideAuthentication(origin, update, updateContext);
        } else {
            subject = performAuthentication(origin, update, updateContext);
        }

        updateContext.subject(update, subject);
    }

    private Subject performOverrideAuthentication(final Origin origin, final PreparedUpdate update, final UpdateContext updateContext) {
        final Set<OverrideCredential> overrideCredentials = update.getCredentials().ofType(OverrideCredential.class);
        final Set<Message> authenticationMessages = Sets.newLinkedHashSet();

        if (!origin.allowAdminOperations()) {
            authenticationMessages.add(UpdateMessages.overrideNotAllowedForOrigin(origin));
        } else if (!ipRanges.isInRipeRange(IpInterval.parse(origin.getFrom()))) {
            authenticationMessages.add(UpdateMessages.overrideOnlyAllowedByDbAdmins());
        }

        if (overrideCredentials.size() != 1) {
            authenticationMessages.add(UpdateMessages.multipleOverridePasswords());
        }

        if (!authenticationMessages.isEmpty()) {
            authenticationFailed(update, updateContext, authenticationMessages);
            return new Subject();
        }

        final OverrideCredential overrideCredential = overrideCredentials.iterator().next();
        for (OverrideCredential.UsernamePassword possibleCredential : overrideCredential.getPossibleCredentials()) {
            final String username = possibleCredential.getUsername();
            try {
                final User user = userDao.getOverrideUser(username);
                if (user.isValidPassword(possibleCredential.getPassword()) && user.getObjectTypes().contains(update.getType())) {
                    updateContext.addMessage(update, UpdateMessages.overrideAuthenticationUsed());
                    return new Subject(Principal.OVERRIDE_MAINTAINER);
                }
            } catch (EmptyResultDataAccessException ignore) {
                loggerContext.logMessage(update, new Message(Messages.Type.INFO, "Unknown override user", username));
            }
        }

        authenticationMessages.add(UpdateMessages.overrideAuthenticationFailed());
        authenticationFailed(update, updateContext, authenticationMessages);

        return new Subject();
    }

    private Subject performAuthentication(final Origin origin, final PreparedUpdate update, final UpdateContext updateContext) {
        final Set<Message> authenticationMessages = Sets.newLinkedHashSet();
        final Set<RpslObject> authenticatedObjects = Sets.newLinkedHashSet();

        final Set<String> passedAuthentications = new HashSet<>();
        final Set<String> failedAuthentications = new HashSet<>();

        if (update.getCredentials().ofType(PasswordCredential.class).size() > 20) {
            authenticationMessages.add(UpdateMessages.tooManyPasswordsSpecified());
        } else {
            for (final AuthenticationStrategy authenticationStrategy : authenticationStrategies) {
                if (authenticationStrategy.supports(update)) {
                    final String authenticationStrategyName = getStrategyName(authenticationStrategy.getClass());

                    try {
                        authenticatedObjects.addAll(authenticationStrategy.authenticate(update, updateContext));
                        passedAuthentications.add(authenticationStrategyName);
                    } catch (AuthenticationFailedException e) {
                        authenticationMessages.addAll(e.getAuthenticationMessages());
                        failedAuthentications.add(authenticationStrategyName);
                    }
                }
            }
        }

        final Set<Principal> principals = Sets.newLinkedHashSet();
        for (final RpslObject authenticatedObject : authenticatedObjects) {
            principals.addAll(getPrincipals(authenticatedObject));
        }

        // TODO: [AH] remove the isDeployed() when we are done migrating power-maintainer tests to syncupdates
        if (!principals.isEmpty() && !origin.isDefaultOverride() && WhoisProfile.isDeployed()) {
            if (!origin.allowAdminOperations() || !ipRanges.isInRipeRange(IpInterval.parse(origin.getFrom()))) {
                authenticationMessages.add(UpdateMessages.ripeMntnerUpdatesOnlyAllowedFromWithinNetwork());
            }
        }

        if (!authenticationMessages.isEmpty()) {
            authenticationFailed(update, updateContext, authenticationMessages);
        }

        return new Subject(principals, passedAuthentications, failedAuthentications);
    }

    private Set<Principal> getPrincipals(final RpslObject authenticatedObject) {
        if (!authenticatedObject.getType().equals(ObjectType.MNTNER)) {
            return Collections.emptySet();
        }

        final Set<Principal> principals = principalsMap.get(authenticatedObject.getKey());
        if (principals == null) {
            return Collections.emptySet();
        }

        return principals;
    }

    private void authenticationFailed(final PreparedUpdate update, final UpdateContext updateContext, final Set<Message> authenticationMessages) {
        if (isPendingAuthentication(update, updateContext)) {
            updateContext.status(update, UpdateStatus.PENDING_AUTHENTICATION);
        } else {
            updateContext.status(update, UpdateStatus.FAILED_AUTHENTICATION);
        }

        for (final Message message : authenticationMessages) {
            updateContext.addMessage(update, message);
        }
    }

    private boolean isPendingAuthentication(final PreparedUpdate preparedUpdate, final UpdateContext updateContext) {
        if (updateContext.hasErrors(preparedUpdate)) {
            return false;
        }

        if (!Action.CREATE.equals(preparedUpdate.getAction())) {
            return false;
        }

        final Set<AuthenticationStrategy> strategies = pendingAuthenticationTypes.get(preparedUpdate.getType());
        if (strategies == null) {
            return false;
        }

        final Subject subject = updateContext.getSubject(preparedUpdate);
        final boolean failedSupportedOnly = Sets.difference(subject.getFailedAuthentications(), strategies).isEmpty();
        final boolean passedAtLeastOneSupported = !Sets.intersection(subject.getPassedAuthentications(), strategies).isEmpty();

        return failedSupportedOnly && passedAtLeastOneSupported;
    }

    private static String getStrategyName(final Class<? extends AuthenticationStrategy> clazz) {
        return clazz.getSimpleName();
    }
}
