package com.github.games647.changeskin.sponge;

public class PomData {

    public static final String ARTIFACT_ID = "${project.parent.artifactId}";
    public static final String NAME = "${project.parent.name}";
    public static final String VERSION = "${project.parent.version}-${git.commit.id.abbrev}";
    public static final String URL = "${project.parent.url}";
    public static final String DESCRIPTION = "${project.parent.description}";
}
