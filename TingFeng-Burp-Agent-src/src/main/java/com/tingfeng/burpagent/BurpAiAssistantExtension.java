/*
 * Copyright 2025 听风sec
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tingfeng.burpagent;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import com.tingfeng.burpagent.ui.AiAssistantTab;

import java.awt.Component;
import java.lang.reflect.Method;
import java.util.Set;

public final class BurpAiAssistantExtension implements BurpExtension {
    @Override
    public void initialize(MontoyaApi api) {
        setExtensionNameCompat(api, "听风 Burp Agent");
        registerSuiteTabCompat(api, "听风 Burp Agent", new AiAssistantTab(api));
        logToOutputCompat(api, "听风 Burp Agent loaded. Local MCP server is disabled. Using official Burp MCP SSE server at http://127.0.0.1:9876 by default.");
    }

    /**
     * Burp/Montoya versions differ on the return type of registerSuiteTab.
     * Calling it directly can cause NoSuchMethodError when the plugin was
     * compiled against a different Montoya API than the one bundled in Burp.
     * Reflection looks up the method by name and parameters only, so it works
     * with both void and non-void registerSuiteTab variants.
     */
    private void registerSuiteTabCompat(MontoyaApi api, String title, Component component) {
        try {
            Object ui = invokeNoArg(api, "userInterface");
            Method method = findMethod(ui.getClass(), "registerSuiteTab", String.class, Component.class);
            method.invoke(ui, title, component);
        } catch (ReflectiveOperationException | RuntimeException e) {
            logToErrorCompat(api, "Failed to register suite tab: " + rootMessage(e));
            throw new IllegalStateException("Failed to register suite tab", e);
        }
    }

    private void setExtensionNameCompat(MontoyaApi api, String name) {
        try {
            Object extension = invokeNoArg(api, "extension");
            Method method = findMethod(extension.getClass(), "setName", String.class);
            method.invoke(extension, name);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Older or incompatible Montoya variants: tab registration is still enough for usability.
        }
    }

    private void logToOutputCompat(MontoyaApi api, String message) {
        try {
            Object logging = invokeNoArg(api, "logging");
            Method method = findMethod(logging.getClass(), "logToOutput", String.class);
            method.invoke(logging, message);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            System.out.println(message);
        }
    }

    private void logToErrorCompat(MontoyaApi api, String message) {
        try {
            Object logging = invokeNoArg(api, "logging");
            Method method = findMethod(logging.getClass(), "logToError", String.class);
            method.invoke(logging, message);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            System.err.println(message);
        }
    }

    private Object invokeNoArg(Object target, String methodName) throws ReflectiveOperationException {
        Method method = findMethod(target.getClass(), methodName);
        return method.invoke(target);
    }

    private Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        for (Class<?> iface : type.getInterfaces()) {
            try {
                Method method = iface.getMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // keep searching
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    @SuppressWarnings("rawtypes")
    public Set enhancedCapabilities() {
        return java.util.Collections.emptySet();
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }
}
