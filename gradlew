#!/bin/bash
JAVA_HOME=${JAVA_HOME:-$(dirname $(dirname $(readlink -f $(which java))))}
exec $JAVA_HOME/bin/java -Xmx2048m -Dfile.encoding=UTF-8 -classpath $(dirname $0)/gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain "$@"
