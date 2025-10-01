package org.leisureup.global.auth.social;

import lombok.*;
import org.leisureup.global.auth.dto.api.*;
import org.leisureup.global.auth.dto.api.GetAppleOidcOpenKeys.*;
import org.leisureup.global.auth.dto.request.SignInUpRequest.*;
import org.leisureup.global.auth.social.internal.*;
import org.leisureup.global.exception.*;
import org.springframework.cache.annotation.*;
import org.springframework.cloud.openfeign.*;
import org.springframework.stereotype.*;
import org.springframework.web.bind.annotation.*;

@FeignClient(
        name = "AppleOidcClient",
        url = "${feign.openid.apple.oidc-key}",
        configuration = AppleApiErrorDecoder.class
)
interface AppleOidcClient {

    @GetMapping("/auth/keys")
    @Cacheable(cacheNames = "apple-oidc-open-keys")
    GetAppleOidcOpenKeys getAppleOidcOpenKeys();
}

@Component
@RequiredArgsConstructor
public class AppleOAuthClient implements OAuthClient {

    private final AppleOidcClient appleOidcClient;
    private final AppleOidcHelper appleOidcHelper;

    /**
     * Apple 은 ID token 검증하는 방식으로 진행한다.
     * <p>
     * (Apple 에는 OIDC user endpoint 가 없음...)
     *
     * @see AppleOidcHelper
     */
    @Override
    public OAuthResponse fetchInfo(String idToken) {

        // apple oidc 공개 키를 가져온다.
        GetAppleOidcOpenKeys publicKeys = appleOidcClient.getAppleOidcOpenKeys();

        // ID token 에서 kid (jwt header 에 존재) 값을 가져온다.
        String kid = appleOidcHelper.getKidClaimsFrom(idToken);

        // ID token 에 적용할 수 있는 공개키를 식별한다.
        AppleOidcKey matchingKey = publicKeys.keys().stream()
                .filter(k -> k.kid().equals(kid))
                .findFirst()
                .orElseThrow(() -> new InvalidIdentityTokenException(
                        401, "No matching key found with given token."
                ));

        // ID token 을 검증한다.
        return appleOidcHelper.getVerifiedInfoFrom(idToken, matchingKey.n(), matchingKey.e());
    }

    @Override
    public AuthType getType() {
        return AuthType.APPLE;
    }
}

