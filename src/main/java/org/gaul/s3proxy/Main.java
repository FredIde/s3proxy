/*
 * Copyright 2014-2017 Andrew Gaul <andrew@gaul.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gaul.s3proxy;

import java.io.Console;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.google.inject.Module;

import org.jclouds.Constants;
import org.jclouds.ContextBuilder;
import org.jclouds.JcloudsVersion;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.location.reference.LocationConstants;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.openstack.swift.v1.blobstore.RegionScopedBlobStoreContext;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private Main() {
        throw new AssertionError("intentionally not implemented");
    }

    private static final class Options {
        @Option(name = "--properties",
                usage = "S3Proxy configuration (required, multiple allowed)")
        private List<File> propertiesFiles;

        @Option(name = "--version", usage = "display version")
        private boolean version;
    }

    public static void main(String[] args) throws Exception {
        Console console = System.console();
        if (console == null) {
            System.setErr(createLoggerErrorPrintStream());
        }

        Options options = new Options();
        CmdLineParser parser = new CmdLineParser(options);
        try {
            parser.parseArgument(args);
        } catch (CmdLineException cle) {
            usage(parser);
        }

        if (options.version) {
            System.err.println(
                    Main.class.getPackage().getImplementationVersion());
            System.exit(0);
        } else if (options.propertiesFiles.isEmpty()) {
            usage(parser);
        }

        S3Proxy.Builder s3ProxyBuilder = null;
        ImmutableMap.Builder<String, Map.Entry<String, BlobStore>> locators =
                ImmutableMap.builder();
        for (File propertiesFile : options.propertiesFiles) {
            Properties properties = new Properties();
            try (InputStream is = new FileInputStream(propertiesFile)) {
                properties.load(is);
            }
            properties.putAll(System.getProperties());

            BlobStore blobStore = createBlobStore(properties);

            blobStore = parseMiddlewareProperties(blobStore, properties);

            String s3ProxyAuthorizationString = properties.getProperty(
                    S3ProxyConstants.PROPERTY_AUTHORIZATION);
            if (AuthenticationType.fromString(s3ProxyAuthorizationString) !=
                    AuthenticationType.NONE) {
                String localIdentity = properties.getProperty(
                        S3ProxyConstants.PROPERTY_IDENTITY);
                String localCredential = properties.getProperty(
                        S3ProxyConstants.PROPERTY_CREDENTIAL);
                locators.put(localIdentity, Maps.immutableEntry(
                        localCredential, blobStore));
            }

            S3Proxy.Builder s3ProxyBuilder2 = parseS3ProxyProperties(properties)
                    .blobStore(blobStore);

            if (s3ProxyBuilder != null &&
                    !s3ProxyBuilder.equals(s3ProxyBuilder2)) {
                System.err.println("Multiple configurations require" +
                        " identical s3proxy properties");
                System.exit(1);
            }
            s3ProxyBuilder = s3ProxyBuilder2;
        }

        S3Proxy s3Proxy;
        try {
            s3Proxy = s3ProxyBuilder.build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            throw e;
        }

        final Map<String, Map.Entry<String, BlobStore>> locator =
                locators.build();
        if (!locator.isEmpty()) {
            s3Proxy.setBlobStoreLocator(new BlobStoreLocator() {
                @Override
                public Map.Entry<String, BlobStore> locateBlobStore(
                        String identity, String container, String blob) {
                    if (identity == null) {
                        if (locator.size() == 1) {
                            return locator.entrySet().iterator().next()
                                    .getValue();
                        }
                        throw new IllegalArgumentException(
                            "cannot use anonymous access with multiple" +
                            " backends");
                    }
                    return locator.get(identity);
                }
            });
        }

        try {
            s3Proxy.start();
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    private static S3Proxy.Builder parseS3ProxyProperties(
            Properties properties) throws URISyntaxException {
        String s3ProxyEndpointString = properties.getProperty(
                S3ProxyConstants.PROPERTY_ENDPOINT);
        String secureEndpoint = properties.getProperty(
                S3ProxyConstants.PROPERTY_SECURE_ENDPOINT);
        String s3ProxyAuthorizationString = properties.getProperty(
                S3ProxyConstants.PROPERTY_AUTHORIZATION);
        if ((s3ProxyEndpointString == null && secureEndpoint == null) ||
                s3ProxyAuthorizationString == null) {
            throw new IllegalArgumentException(
                    "Properties file must contain:\n" +
                    S3ProxyConstants.PROPERTY_AUTHORIZATION + "\n" +
                    "and one of\n" +
                    S3ProxyConstants.PROPERTY_ENDPOINT + "\n" +
                    S3ProxyConstants.PROPERTY_SECURE_ENDPOINT);
        }

        String s3ProxyServicePath = properties.getProperty(
                S3ProxyConstants.PROPERTY_SERVICE_PATH);

        AuthenticationType s3ProxyAuthorization =
                AuthenticationType.fromString(s3ProxyAuthorizationString);
        String localIdentity = null;
        String localCredential = null;
        switch (s3ProxyAuthorization) {
        case AWS_V2:
        case AWS_V4:
        case AWS_V2_OR_V4:
            localIdentity = properties.getProperty(
                    S3ProxyConstants.PROPERTY_IDENTITY);
            localCredential = properties.getProperty(
                    S3ProxyConstants.PROPERTY_CREDENTIAL);
            if (localIdentity == null || localCredential == null) {
                throw new IllegalArgumentException("Must specify both " +
                        S3ProxyConstants.PROPERTY_IDENTITY + " and " +
                        S3ProxyConstants.PROPERTY_CREDENTIAL +
                        " when using authentication");
            }
            break;
        case NONE:
            break;
        default:
            throw new IllegalArgumentException(
                    S3ProxyConstants.PROPERTY_AUTHORIZATION +
                    " invalid value, was: " + s3ProxyAuthorization);
        }

        String keyStorePath = properties.getProperty(
                S3ProxyConstants.PROPERTY_KEYSTORE_PATH);
        String keyStorePassword = properties.getProperty(
                S3ProxyConstants.PROPERTY_KEYSTORE_PASSWORD);
        String virtualHost = properties.getProperty(
                S3ProxyConstants.PROPERTY_VIRTUAL_HOST);
        String v4MaxNonChunkedRequestSize = properties.getProperty(
                S3ProxyConstants.PROPERTY_V4_MAX_NON_CHUNKED_REQUEST_SIZE);
        String ignoreUnknownHeaders = properties.getProperty(
                S3ProxyConstants.PROPERTY_IGNORE_UNKNOWN_HEADERS);
        String corsAllowAll = properties.getProperty(
                S3ProxyConstants.PROPERTY_CORS_ALLOW_ALL);

        S3Proxy.Builder s3ProxyBuilder = S3Proxy.builder();
        if (s3ProxyEndpointString != null) {
            s3ProxyBuilder.endpoint(new URI(s3ProxyEndpointString));
        }
        if (secureEndpoint != null) {
            s3ProxyBuilder.secureEndpoint(new URI(secureEndpoint));
        }
        if (localIdentity != null || localCredential != null) {
            s3ProxyBuilder.awsAuthentication(
                    s3ProxyAuthorization, localIdentity,
                    localCredential);
        }
        if (keyStorePath != null || keyStorePassword != null) {
            s3ProxyBuilder.keyStore(keyStorePath, keyStorePassword);
        }
        if (!Strings.isNullOrEmpty(virtualHost)) {
            s3ProxyBuilder.virtualHost(virtualHost);
        }
        if (v4MaxNonChunkedRequestSize != null) {
            s3ProxyBuilder.v4MaxNonChunkedRequestSize(Long.parseLong(
                    v4MaxNonChunkedRequestSize));
        }
        if (!Strings.isNullOrEmpty(ignoreUnknownHeaders)) {
            s3ProxyBuilder.ignoreUnknownHeaders(Boolean.parseBoolean(
                    ignoreUnknownHeaders));
        }
        if (corsAllowAll != null) {
            s3ProxyBuilder.corsAllowAll(Boolean.parseBoolean(
                    corsAllowAll));
        }
        if (s3ProxyServicePath != null) {
            s3ProxyBuilder.servicePath(s3ProxyServicePath);
        }

        return s3ProxyBuilder;
    }

    private static BlobStore parseMiddlewareProperties(BlobStore blobStore,
            Properties properties) throws IOException {
        Properties altProperties = new Properties();
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            String key = (String) entry.getKey();
            if (key.startsWith(S3ProxyConstants.PROPERTY_ALT_JCLOUDS_PREFIX)) {
                key = key.substring(
                        S3ProxyConstants.PROPERTY_ALT_JCLOUDS_PREFIX.length());
                altProperties.put(key, (String) entry.getValue());
            }
        }

        String eventualConsistency = properties.getProperty(
                S3ProxyConstants.PROPERTY_EVENTUAL_CONSISTENCY);
        if ("true".equalsIgnoreCase(eventualConsistency)) {
            BlobStore altBlobStore = createBlobStore(altProperties);
            int delay = Integer.parseInt(properties.getProperty(
                    S3ProxyConstants.PROPERTY_EVENTUAL_CONSISTENCY_DELAY,
                    "5"));
            double probability = Double.parseDouble(properties.getProperty(
                    S3ProxyConstants.PROPERTY_EVENTUAL_CONSISTENCY_PROBABILITY,
                    "1.0"));
            System.err.println("Emulating eventual consistency with delay " +
                    delay + " seconds and probability " + (probability * 100) +
                    "%");
            blobStore = EventualBlobStore.newEventualBlobStore(
                    blobStore, altBlobStore,
                    Executors.newScheduledThreadPool(1),
                    delay, TimeUnit.SECONDS, probability);
        }

        String nullBlobStore = properties.getProperty(
                S3ProxyConstants.PROPERTY_NULL_BLOBSTORE);
        if ("true".equalsIgnoreCase(nullBlobStore)) {
            System.err.println("Using null storage backend");
            blobStore = NullBlobStore.newNullBlobStore(blobStore);
        }

        String readOnlyBlobStore = properties.getProperty(
                S3ProxyConstants.PROPERTY_READ_ONLY_BLOBSTORE);
        if ("true".equalsIgnoreCase(readOnlyBlobStore)) {
            System.err.println("Using read-only storage backend");
            blobStore = ReadOnlyBlobStore.newReadOnlyBlobStore(blobStore);
        }

        return blobStore;
    }

    private static PrintStream createLoggerErrorPrintStream() {
        return new PrintStream(System.err) {
            private final StringBuilder builder = new StringBuilder();

            @Override
            public void print(final String string) {
                logger.error(string);
            }

            @Override
            public void write(byte[] buf, int off, int len) {
                for (int i = off; i < len; ++i) {
                    char ch = (char) buf[i];
                    if (ch == '\n') {
                        if (builder.length() != 0) {
                            print(builder.toString());
                            builder.setLength(0);
                        }
                    } else {
                        builder.append(ch);
                    }
                }
            }
        };
    }

    private static BlobStore createBlobStore(Properties properties)
            throws IOException {
        String provider = properties.getProperty(Constants.PROPERTY_PROVIDER);
        String identity = properties.getProperty(Constants.PROPERTY_IDENTITY);
        String credential = properties.getProperty(
                Constants.PROPERTY_CREDENTIAL);
        String endpoint = properties.getProperty(Constants.PROPERTY_ENDPOINT);
        properties.remove(Constants.PROPERTY_ENDPOINT);
        String region = properties.getProperty(
                LocationConstants.PROPERTY_REGION);

        if (provider.equals("filesystem") || provider.equals("transient")) {
            // local blobstores do not require credentials
            identity = Strings.nullToEmpty(identity);
            credential = Strings.nullToEmpty(credential);
        } else if (provider.equals("google-cloud-storage")) {
            File credentialFile = new File(credential);
            if (credentialFile.exists()) {
                credential = Files.asCharSource(credentialFile,
                        StandardCharsets.UTF_8).read();
            }
            properties.remove(Constants.PROPERTY_CREDENTIAL);
        }

        if (provider == null || identity == null || credential == null) {
            System.err.println("Properties file must contain:\n" +
                    Constants.PROPERTY_PROVIDER + "\n" +
                    Constants.PROPERTY_IDENTITY + "\n" +
                    Constants.PROPERTY_CREDENTIAL + "\n");
        }

        properties.setProperty(Constants.PROPERTY_USER_AGENT,
                String.format("s3proxy/%s jclouds/%s java/%s",
                        Main.class.getPackage().getImplementationVersion(),
                        JcloudsVersion.get(),
                        System.getProperty("java.version")));

        ContextBuilder builder = ContextBuilder
                .newBuilder(provider)
                .credentials(identity, credential)
                .modules(ImmutableList.<Module>of(new SLF4JLoggingModule()))
                .overrides(properties);
        if (!Strings.isNullOrEmpty(endpoint)) {
            builder = builder.endpoint(endpoint);
        }

        BlobStoreContext context = builder.build(BlobStoreContext.class);
        BlobStore blobStore = context.getBlobStore();
        if (context instanceof RegionScopedBlobStoreContext &&
                region != null) {
            blobStore = ((RegionScopedBlobStoreContext) context)
                    .getBlobStore(region);
        }
        return blobStore;
    }

    private static void usage(CmdLineParser parser) {
        System.err.println("Usage: s3proxy [options...]");
        parser.printUsage(System.err);
        System.exit(1);
    }
}
