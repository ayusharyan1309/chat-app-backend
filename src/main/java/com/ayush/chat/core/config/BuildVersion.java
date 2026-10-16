package com.ayush.chat.core.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * The platform version from Maven build-info (single source: the pom), or {@code dev} when running
 * from an IDE without build-info.
 */
@Component
public class BuildVersion {

    private final String version;

    public BuildVersion(ObjectProvider<BuildProperties> build) {
        BuildProperties properties = build.getIfAvailable();
        this.version = properties != null ? properties.getVersion() : "dev";
    }

    public String get() {
        return version;
    }
}
