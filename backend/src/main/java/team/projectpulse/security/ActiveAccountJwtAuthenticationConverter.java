package team.projectpulse.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;
import team.projectpulse.user.PeerEvaluationUser;
import team.projectpulse.user.UserRepository;

/**
 * Refuses a bearer token whose account is no longer active (FR-SEC-active-account).
 *
 * <p>A token is valid for two hours after login, and the resource server used to check only its signature and
 * expiry, so a deactivated student or instructor kept her access until the token aged out. This converter runs
 * for every bearer-token request, loads the user the token's {@code userId} claim names, and throws
 * {@link DisabledException} when that account is disabled or no longer exists. Throwing is the contract of an
 * authentication step: {@code BearerTokenAuthenticationFilter} hands the exception to
 * {@link CustomBearerTokenAuthenticationEntryPoint}, which routes it through the handler exception resolver to
 * {@code ExceptionHandlerAdvice.handleAccountStatusException}, so the caller gets the same {@code 401}
 * "User account is abnormal." envelope a disabled login gets.
 *
 * <p>An active account is converted by the {@link JwtAuthenticationConverter} bean this wraps, which keeps the
 * {@code authorities} claim name and the empty authority prefix the tokens are issued with.
 */
@Component
public class ActiveAccountJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    static final String USER_ID_CLAIM = "userId";

    private final JwtAuthenticationConverter delegate;
    private final UserRepository userRepository;


    public ActiveAccountJwtAuthenticationConverter(JwtAuthenticationConverter delegate, UserRepository userRepository) {
        this.delegate = delegate;
        this.userRepository = userRepository;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Integer userId = readUserId(jwt);
        PeerEvaluationUser user = userId == null ? null : this.userRepository.findById(userId).orElse(null);
        if (user == null) {
            // A token whose user row is gone (deleted after login) is refused the same way a deactivated one is
            throw new DisabledException("User account no longer exists.");
        }
        if (!user.isEnabled()) {
            throw new DisabledException("User account is deactivated.");
        }
        return this.delegate.convert(jwt);
    }

    /**
     * The claim is issued as the user's integer id, but the decoded JSON number may arrive as a Long, so read it
     * the way {@code UserUtils.getUserId} does.
     */
    private static Integer readUserId(Jwt jwt) {
        Object claim = jwt.getClaim(USER_ID_CLAIM);
        if (claim instanceof Integer i) return i;
        if (claim instanceof Long l) return l.intValue();
        if (claim instanceof String s) {
            try {
                return Integer.valueOf(s);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

}
