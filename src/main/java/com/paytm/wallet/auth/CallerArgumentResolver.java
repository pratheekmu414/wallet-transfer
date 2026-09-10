package com.paytm.wallet.auth;

import com.paytm.wallet.domain.ApiException;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Injects the {@link Caller} that {@link BearerAuthFilter} attached to the request. */
@Component
public class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(Caller.class);
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {

        Object caller = webRequest.getAttribute(
                BearerAuthFilter.CALLER_ATTRIBUTE, NativeWebRequest.SCOPE_REQUEST);
        if (caller == null) {
            // Should not happen for filtered paths, but fail closed rather than NPE.
            throw ApiException.unauthorized("unauthenticated request");
        }
        return caller;
    }
}
