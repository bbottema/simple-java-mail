package org.simplejavamail.springbootsupport;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @deprecated Don't use this class directly, it is only used to generate the file "spring-configuration-metadata.json" used for IDE hints for the application.properties file.
 */
@SuppressWarnings("ConfigurationProperties")
@ConfigurationProperties(prefix = "simplejavamail")
@Getter
@Setter
@Deprecated
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value="UUF_UNUSED_FIELD", justification="not used at runtime")
public class SimpleJavaMailProperties {

    private Javaxmail javaxmail;
    private String transportstrategy;

    private Smtp smtp;
    private Proxy proxy;
    private Defaults defaults;
    private Smime smime;
    private Dkim dkim;
    private Embeddedimages embeddedimages;

    private Disable disable;
    private Custom custom;
    private Transport transport;
    private Opportunistic opportunistic;

    /**
     * @deprecated See {@link SimpleJavaMailProperties}
     */
    @Getter
    @Setter
    public static class Javaxmail {
        private String debug;
    }

    /**
     * @deprecated See {@link SimpleJavaMailProperties}
     */
    @Getter
    @Setter
    public static class Smtp {
        private String host;
        private String port;
        private String username;
        private String password;
    }

    /**
     * @deprecated See {@link SimpleJavaMailProperties}
     */
    @Getter
    @Setter
    public static class Proxy {
        private String host;
        private String port;
        private String username;
        private String password;
        private Socks5bridge socks5bridge;

        /**
         * @deprecated See {@link SimpleJavaMailProperties}
         */
        @Getter
        @Setter
        public static class Socks5bridge {
            private String port;
        }
    }

    /**
     * @deprecated See {@link SimpleJavaMailProperties}
     */
    @Getter
    @Setter
    public static class Defaults {
        private Content content;
        private String subject;
        private Recipient from;
        private Recipient replyto;
        private Recipient bounceto;
        private Recipient to;
        private Recipient cc;
        private Recipient bcc;
        private String poolsize;
        private PoolsizeMore poolsizeMore;
        private Connectionpool connectionpool;
        private String sessiontimeoutmillis;
        private String trustallhosts;
        private String trustedhosts;
        private String verifyserveridentity;

        /**
         * @deprecated See {@link SimpleJavaMailProperties}
         */
        @Getter
        @Setter
        public static class Content {
            private Transfer transfer;

            /**
             * @deprecated See {@link SimpleJavaMailProperties}
             */
            @Getter
            @Setter
            public static class Transfer {
                private String encoding;
            }
        }

        /**
         * @deprecated See {@link SimpleJavaMailProperties}
         */
        @Getter
        @Setter
        public static class PoolsizeMore {
            private String keepalivetime;
        }

        /**
         * @deprecated See {@link SimpleJavaMailProperties}
         */
        @Getter
        @Setter
        public static class Connectionpool {
            private Clusterkey clusterkey;
            private String coresize;
            private String maxsize;
            private Claimtimeout claimtimeout;
            private Expireafter expireafter;
            private Loadbalancing loadbalancing;

            /**
             * @deprecated See {@link SimpleJavaMailProperties}
             */
            @Getter
            @Setter
            public static class Clusterkey {
                private String uuid;
            }

            /**
             * @deprecated See {@link SimpleJavaMailProperties}
             */
            @Getter
            @Setter
            public static class Claimtimeout {
                private String millis;
            }

            /**
             * @deprecated See {@link SimpleJavaMailProperties}
             */
            @Getter
            @Setter
            public static class Expireafter {
                private String millis;
            }

            /**
             * @deprecated See {@link SimpleJavaMailProperties}
             */
            @Getter
            @Setter
            public static class Loadbalancing {
                private String strategy;
            }
        }

        /**
         * @deprecated See {@link SimpleJavaMailProperties}
         */
        @Getter
        @Setter
        public static class Recipient {
            private String name;
            private String address;
        }
    }

    /**
     * @deprecated See {@link SimpleJavaMailProperties}
     */
    @Getter
    @Setter
    public static class Smime {
        private Signing signing;
        private Encryption encryption;

        /**
         * @deprecated See {@link SimpleJavaMailProperties}
         */
        @Getter
        @Setter
        public static class Signing {
            private String keystore;
            private String keystorePassword;
            private String keyAlias;
            private String keyPassword;
            private String algorithm;
        }

        /**
         * @deprecated See {@link SimpleJavaMailProperties}
         */
        @Getter
        @Setter
        public static class Encryption {
            private String certificate;
            private String keyEncapsulationAlgorithm;
            private String cipher;
        }
    }

    /**
     * @deprecated See {@link SimpleJavaMailProperties}
     */
    @Getter
    @Setter
    public static class Dkim {
        private Signing signing;

        /**
         * @deprecated See {@link SimpleJavaMailProperties}
         */
        @Getter
        @Setter
        public static class Signing {
            private String privateKeyFileOrData;
            private String selector;
            private String signingDomain;
            private String useLengthParam;
            private String excludedHeadersFromDefaultSigningList;
            private String headerCanonicalization;
            private String bodyCanonicalization;
            private String algorithm;
        }
    }

    /**
     * @deprecated See {@link SimpleJavaMailProperties}
     */
    @Getter
    @Setter
    public static class Embeddedimages {

        private Dynamicresolution dynamicresolution;

        /**
         * @deprecated See {@link SimpleJavaMailProperties}
         */
        @Getter
        @Setter
        public static class Dynamicresolution {
            private String mustbesuccesful;
            private Enable enable;
            private Base base;
            private Outside outside;

            /**
             * @deprecated See {@link SimpleJavaMailProperties}
             */
            @Getter
            @Setter
            public static class Enable {
                private String dir;
                private String url;
                private String classpath;
            }

            /**
             * @deprecated See {@link SimpleJavaMailProperties}
             */
            @Getter
            @Setter
            public static class Base {
                private String dir;
                private String url;
                private String classpath;
            }

            /**
             * @deprecated See {@link SimpleJavaMailProperties}
             */
            @Getter
            @Setter
            public static class Outside {
                private Base base;

                /**
                 * @deprecated See {@link SimpleJavaMailProperties}
                 */
                @Getter
                @Setter
                public static class Base {
                    private String dir;
                    private String classpath;
                    private String url;
                }
            }
        }
    }

    /**
     * @deprecated See {@link SimpleJavaMailProperties}
     */
    @Getter
    @Setter
    public static class Disable {
        private All all;

        /**
         * @deprecated See {@link SimpleJavaMailProperties}
         */
        @Getter
        @Setter
        public static class All {
            private String clientvalidation;
        }
    }

    /**
     * @deprecated See {@link SimpleJavaMailProperties}
     */
    @Getter
    @Setter
    public static class Custom {
        private Sslfactory sslfactory;

        /**
         * @deprecated See {@link SimpleJavaMailProperties}
         */
        @Getter
        @Setter
        public static class Sslfactory {
            private String clazz;
        }
    }

    /**
     * @deprecated See {@link SimpleJavaMailProperties}
     */
    @Getter
    @Setter
    public static class Transport {
        private Mode mode;

        /**
         * @deprecated See {@link SimpleJavaMailProperties}
         */
        @Getter
        @Setter
        public static class Mode {
            private Logging logging;

            /**
             * @deprecated See {@link SimpleJavaMailProperties}
             */
            @Getter
            @Setter
            public static class Logging {
                private String only;
            }
        }
    }

    /**
     * @deprecated See {@link SimpleJavaMailProperties}
     */
    @Getter
    @Setter
    public static class Opportunistic {
        private String tls;
    }
}