package com.platform.web.aspect;

import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import com.platform.web.annotation.RequireRole;
import com.platform.web.context.UserContext;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 角色校验切面 —— 校验当前用户是否具备要求的角色。
 * 若用户角色与注解指定角色无交集，抛出 NO_PERMISSION 异常。
 */
@Aspect
@Component
public class RequireRoleAspect {

    @Before("@annotation(requireRole)")
    public void checkRole(RequireRole requireRole) {
        List<String> userRoles = UserContext.getRoles();

        if (userRoles.isEmpty()) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }

        List<String> requiredRoles = Arrays.asList(requireRole.value());

        boolean hasRole = userRoles.stream().anyMatch(requiredRoles::contains);
        if (!hasRole) {
            throw new BizException(ErrorCode.NO_PERMISSION,
                    "需要角色: " + String.join(",", requiredRoles)
                            + "，当前角色: " + String.join(",", userRoles));
        }
    }
}
