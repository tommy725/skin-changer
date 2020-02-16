package com.github.games647.changeskin.core;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class CooldownServiceTest {

    private CooldownService cooldown;

    @Before
    public void setUp() throws Exception {
        cooldown = new CooldownService(Duration.ofSeconds(1));
    }

    @Test
    public void testEmpty() throws Exception {
        assertThat(cooldown.isTracked(UUID.randomUUID()), is(false));
    }

    @Test
    public void testTracked() throws Exception {
        UUID uniqueId = UUID.randomUUID();
        cooldown.trackPlayer(uniqueId);
        assertThat(cooldown.isTracked(uniqueId), is(true));
    }

    @Test
    public void testExpired() throws Exception {
        UUID uniqueId = UUID.randomUUID();
        cooldown.trackPlayer(uniqueId);

        TimeUnit.SECONDS.sleep(1);

        assertThat(cooldown.isTracked(uniqueId), is(false));
    }
}
