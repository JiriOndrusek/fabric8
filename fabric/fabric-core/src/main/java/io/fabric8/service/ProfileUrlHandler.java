/**
 *  Copyright 2005-2016 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collection;

import io.fabric8.api.CuratorComplete;
import io.fabric8.api.InvalidComponentException;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;

import io.fabric8.api.Container;
import io.fabric8.api.FabricService;
import io.fabric8.api.Profile;
import io.fabric8.api.jcip.ThreadSafe;
import io.fabric8.api.scr.Validatable;
import io.fabric8.api.scr.ValidatingReference;
import io.fabric8.api.scr.ValidationSupport;

import io.fabric8.api.gravia.IllegalStateAssertion;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.url.AbstractURLStreamHandlerService;
import org.osgi.service.url.URLStreamHandlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ThreadSafe
@Component(name = "io.fabric8.profile2.urlhandler", label = "Fabric8 Profile URL Handler", immediate = true, metatype = false)
@Service(URLStreamHandlerService.class)
@Properties({
        @Property(name = "url.handler.protocol", value = "profile2")
})
public final class ProfileUrlHandler extends AbstractURLStreamHandlerService implements Validatable {

    private static final String SYNTAX = "profile2:<resource name>";

    public static final Logger LOGGER = LoggerFactory.getLogger(ProfileUrlHandler.class);

    @Reference(referenceInterface = FabricService.class)
    private final ValidatingReference<FabricService> fabricService = new ValidatingReference<FabricService>();

    @Reference(referenceInterface = CuratorComplete.class)
    private final ValidatingReference<CuratorComplete> platformReadyService = new ValidatingReference<>();

    private final ValidationSupport active = new ValidationSupport();
    private BundleContext context;

    @Activate
    void activate(BundleContext bundleContext) {
        context = bundleContext;
        active.setValid();
    }

    @Deactivate
    void deactivate() {
        active.setInvalid();
    }

    @Override
    public boolean isValid() {
        return active.isValid();
    }

    @Override
    public void assertValid() {
        active.assertValid();
    }

    @Override
    public URLConnection openConnection(URL url) throws IOException {
        assertValid();
        return new Connection(url);
    }

    private class Connection extends URLConnection {

        public Connection(URL url) throws MalformedURLException {
            super(url);
            if (url.getPath() == null || url.getPath().trim().length() == 0) {
                throw new MalformedURLException("Path can not be null or empty. Syntax: " + SYNTAX);
            }
            if ((url.getHost() != null && url.getHost().length() > 0) || url.getPort() != -1) {
                throw new MalformedURLException("Unsupported host/port in profile url");
            }
        }

        @Override
        public void connect() throws IOException {
            assertValid();
        }

        @Override
        public InputStream getInputStream() throws IOException {
            assertValid();
            String path = url.getPath();
            byte[] bytes = null;
            boolean resolved = false;
            final int MAX_RETRIES = 10;
            int iteration = 0;
            String containerId = null;
            String query = url.getQuery();
            if (query != null && query.startsWith("containerProfileId=container-")) {
                containerId = query.substring("containerProfileId=container-".length());
            }
            while(!resolved && iteration < MAX_RETRIES) {
                try {
                    Container container = null;
                    if (containerId != null) {
                        container = fabricService.get().getContainer(containerId);
                    } else {
                        container = fabricService.get().getCurrentContainer();
                    }
                    Profile overlayProfile = container.getOverlayProfile();
                    bytes = overlayProfile.getFileConfiguration(path);
                    resolved = true;
                } catch (IllegalStateException e) {
                    LOGGER.warn("Connection to fabric service is temporarily not available. Retrying...: " + e.getMessage());
                    try {
                        iteration = iteration + 1;
                        Thread.sleep(7000L);
                    } catch (InterruptedException e1) {
                        Thread.currentThread().interrupt();
                        throw new IOException("'profile:' resolution operation interrupted.", e1);
                    }
                } catch (InvalidComponentException e) {
                    // we'll try to use newer OSGi services from OSGi assuming that our parent
                    // ProfileUrlService was @Deactivated (it may happen e.g., during `fabric:join`)
                    try {
                        Collection<ServiceReference<URLStreamHandlerService>> refs = context.getServiceReferences(URLStreamHandlerService.class, "(url.handler.protocol=profile)");
                        if (refs.size() > 0) {
                            ServiceReference<URLStreamHandlerService> ref = refs.iterator().next();
                            URLStreamHandlerService handler = context.getService(ref);
                            if (handler != ProfileUrlHandler.this) {
                                // we assume not to fall into endless loop
                                LOGGER.info("profile: URL handler changed (" + ProfileUrlHandler.this + " -> " + handler + ")");
                                URL newUrl = new URL(url.toString().replaceFirst("^profile2:", "profile:"));
                                InputStream stream = handler.openConnection(newUrl).getInputStream();
                                context.ungetService(ref);
                                return stream;
                            }
                        }
                    } catch (InvalidSyntaxException e2) {
                        LOGGER.warn("Invalid filter syntax: " + e.getMessage(), e2);
                    }
                    LOGGER.warn("Connection to fabric service is temporarily not available. Retrying...: " + e.getMessage());
                    try {
                        iteration = iteration + 1;
                        Thread.sleep(2000L);
                    } catch (InterruptedException e1) {
                        Thread.currentThread().interrupt();
                        throw new IOException("'profile:' resolution operation interrupted.", e1);
                    }
                }
            }
            IllegalStateAssertion.assertNotNull(bytes, "Resource " + path + " does not exist in the profile overlay.");
            return new ByteArrayInputStream(bytes);
        }
    }

    void bindFabricService(FabricService fabricService) {
        this.fabricService.bind(fabricService);
    }

    void unbindFabricService(FabricService fabricService) {
        this.fabricService.unbind(fabricService);
    }

    void bindPlatformReadyService(CuratorComplete platformReadyService) {
        this.platformReadyService.bind(platformReadyService);
    }

    void unbindPlatformReadyService(CuratorComplete platformReadyService) {
        this.platformReadyService.unbind(platformReadyService);
    }

}
