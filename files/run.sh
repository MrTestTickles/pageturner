#!/bin/bash
export JAVA_HOME="/Users/s260555/Downloads/New-Folder-With-items/jdk-21.0.7.jdk/Contents/Home"
export PATH="$HOME/mvn/bin:$JAVA_HOME/bin:$PATH"
DIR="$(cd "$(dirname "$0")" && pwd)"
mvn -f "$DIR/pom.xml" javafx:run
