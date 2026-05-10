#!/bin/sh
# MoRealm build script — delegates to local Gradle installation
GRADLE_HOME="C:/Users/test/.gradle/wrapper/dists/gradle-8.14-all/8mguqc37c200i71ledpgw8n5m/gradle-8.14"
exec "$GRADLE_HOME/bin/gradle" "$@"
