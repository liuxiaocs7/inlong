/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.manager.web.auth;

import org.apache.inlong.manager.common.auth.InlongShiro;
import org.apache.inlong.manager.common.util.SHAUtils;
import org.apache.inlong.manager.service.tenant.InlongTenantService;
import org.apache.inlong.manager.service.user.InlongRoleService;
import org.apache.inlong.manager.service.user.TenantRoleService;
import org.apache.inlong.manager.service.user.UserService;
import org.apache.inlong.manager.web.auth.openapi.OpenAPIAuthenticatingRealm;
import org.apache.inlong.manager.web.auth.openapi.OpenAPIFilter;
import org.apache.inlong.manager.web.auth.tenant.TenantAuthenticatingFilter;
import org.apache.inlong.manager.web.auth.tenant.TenantAuthenticatingRealm;
import org.apache.inlong.manager.web.auth.web.AuthenticationFilter;
import org.apache.inlong.manager.web.auth.web.WebAuthorizingRealm;

import org.apache.shiro.authc.credential.CredentialsMatcher;
import org.apache.shiro.authc.credential.HashedCredentialsMatcher;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.spring.security.interceptor.AuthorizationAttributeSourceAdvisor;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.apache.shiro.web.mgt.WebSecurityManager;
import org.apache.shiro.web.session.mgt.DefaultWebSessionManager;
import org.apache.shiro.web.session.mgt.WebSessionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.servlet.Filter;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inlong shiro service layer implementation.
 */
@Component
@ConditionalOnProperty(name = "type", prefix = "inlong.auth", havingValue = "default")
public class InlongShiroImpl implements InlongShiro {

    private static final String FILTER_NAME_WEB = "authWeb";
    private static final String FILTER_NAME_API = "authAPI";

    private static final String FILTER_NAME_TENANT = "authTenant";

    @Autowired
    private UserService userService;

    @Autowired
    private InlongRoleService inlongRoleService;

    @Autowired
    private TenantRoleService tenantRoleService;

    @Autowired
    private InlongTenantService tenantService;

    @Value("${openapi.auth.enabled:false}")
    private Boolean openAPIAuthEnabled;

    @Override
    public WebSecurityManager getWebSecurityManager() {
        return new DefaultWebSecurityManager();
    }

    @Override
    public Collection<Realm> getShiroRealms() {
        AuthorizingRealm webRealm = new WebAuthorizingRealm(userService);
        webRealm.setCredentialsMatcher(getCredentialsMatcher());
        Realm apiRealm = new OpenAPIAuthenticatingRealm(userService);
        Realm tenantRealm = new TenantAuthenticatingRealm(tenantRoleService, inlongRoleService,
                userService, tenantService);
        return Arrays.asList(webRealm, apiRealm, tenantRealm);
    }

    @Override
    public WebSessionManager getWebSessionManager() {
        return new DefaultWebSessionManager();
    }

    @Override
    public CredentialsMatcher getCredentialsMatcher() {
        HashedCredentialsMatcher hashedCredentialsMatcher = new HashedCredentialsMatcher();
        hashedCredentialsMatcher.setHashAlgorithmName(SHAUtils.ALGORITHM_NAME);
        hashedCredentialsMatcher.setHashIterations(1024);
        return hashedCredentialsMatcher;
    }

    @Override
    public ShiroFilterFactoryBean getShiroFilter(SecurityManager securityManager) {
        ShiroFilterFactoryBean shiroFilterFactoryBean = new ShiroFilterFactoryBean();
        shiroFilterFactoryBean.setSecurityManager(securityManager);
        // anon: can be accessed by anyone, authc: only authentication is successful can be accessed
        Map<String, Filter> filters = new LinkedHashMap<>();

        // request filter
        filters.put(FILTER_NAME_WEB, new AuthenticationFilter());

        shiroFilterFactoryBean.setFilters(filters);
        Map<String, String> pathDefinitions = new LinkedHashMap<>();

        // login, register request
        pathDefinitions.put("/api/anno/**/*", "anon");

        // swagger api
        pathDefinitions.put("/doc.html", "anon");
        pathDefinitions.put("/v2/api-docs/**/**", "anon");
        pathDefinitions.put("/webjars/**/*", "anon");
        pathDefinitions.put("/swagger-resources/**/*", "anon");
        pathDefinitions.put("/swagger-resources", "anon");

        // openapi
        if (openAPIAuthEnabled) {
            filters.put(FILTER_NAME_API, new OpenAPIFilter());
            pathDefinitions.put("/openapi/**/*", genFiltersInOrder(FILTER_NAME_API, FILTER_NAME_TENANT));
        } else {
            pathDefinitions.put("/openapi/**/*", "anon");
        }

        // other web
        pathDefinitions.put("/**", genFiltersInOrder(FILTER_NAME_WEB, FILTER_NAME_TENANT));

        // tenant filter
        filters.put(FILTER_NAME_TENANT, new TenantAuthenticatingFilter());

        shiroFilterFactoryBean.setFilterChainDefinitionMap(pathDefinitions);
        return shiroFilterFactoryBean;
    }

    @Override
    public AuthorizationAttributeSourceAdvisor getAuthorizationAttributeSourceAdvisor(SecurityManager securityManager) {
        AuthorizationAttributeSourceAdvisor authorizationAttributeSourceAdvisor =
                new AuthorizationAttributeSourceAdvisor();
        authorizationAttributeSourceAdvisor.setSecurityManager(securityManager);
        return authorizationAttributeSourceAdvisor;
    }

    private String genFiltersInOrder(String... filterNames) {
        if (filterNames.length == 1) {
            return filterNames[0];
        }

        StringBuilder builder = new StringBuilder();
        for (String filterName : filterNames) {
            builder.append(filterName).append(",");
        }
        builder.deleteCharAt(builder.length() - 1);
        return builder.toString();
    }
}
