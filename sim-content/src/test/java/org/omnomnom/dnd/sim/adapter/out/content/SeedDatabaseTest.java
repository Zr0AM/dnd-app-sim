package org.omnomnom.dnd.sim.adapter.out.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class SeedDatabaseTest {

    @Test
    void buildsTheSeedDatabaseAndLoadsEveryMonster() throws Exception {
        long start = System.nanoTime();
        try (Connection c = SeedDatabase.open(); Statement st = c.createStatement()) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            System.out.println("seed database built in " + ms + " ms");
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM Monster WHERE active = 1")) {
                rs.next();
                System.out.println("active monsters: " + rs.getInt(1));
                assertThat(rs.getInt(1)).isGreaterThan(300);
            }
            try (ResultSet rs = st.executeQuery("PRAGMA foreign_keys")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }
}
