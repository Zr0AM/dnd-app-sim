package org.omnomnom.dnd.sim.adapter.out.content;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Builds the SRD seed database in memory: the committed schema plus every seed file, with foreign keys enforced. This
 * mirrors {@code buildSeedDatabase} in {@code sim/src/content/load-db.ts}, so the service reads exactly the data the
 * TypeScript sim reads. The SQL lives in {@code sim-content/src/main/resources/db} (copied from {@code Zr0AM/dnd-app} by
 * {@code scripts/sync-seeds.sh}).
 */
final class SeedDatabase {

    /** The live {@code Item} table is assumed to exist in production, so it is stubbed for the seeds. */
    private static final String ITEM_STUB = """
            CREATE TABLE Item (
              itemID INTEGER PRIMARY KEY, itemName TEXT NOT NULL, itemRarity TEXT, itemCost REAL,
              itemType TEXT, itemRestrictions TEXT, itemAttunement TEXT, itemSource TEXT, itemUrl TEXT,
              itemVisualDesc TEXT, itemShopkeeperDesc TEXT, active INTEGER NOT NULL DEFAULT 1,
              itemDescription TEXT, itemDescriptionSource TEXT)""";

    /** Seed files in load order (sorted by name, as upstream does). */
    static final List<String> SEED_FILES = List.of(
            "01-reference.sql", "02-equipment.sql", "03-classes.sql", "04-spells.sql", "05-origins.sql",
            "06-monsters.sql", "07-items-backfill.sql");

    private SeedDatabase() {}

    static Connection open() {
        try {
            Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute(ITEM_STUB);
                conn.setAutoCommit(false);
                st.executeUpdate(resource("/db/schema-draft.sql"));
                for (String file : SEED_FILES) {
                    st.executeUpdate(resource("/db/seed/" + file));
                }
                conn.commit();
                conn.setAutoCommit(true);
            }
            return conn;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to build the seed database", e);
        }
    }

    private static String resource(String path) {
        try (InputStream in = SeedDatabase.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + path + " (run scripts/sync-seeds.sh)");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
