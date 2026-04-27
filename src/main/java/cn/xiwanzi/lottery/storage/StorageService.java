package cn.xiwanzi.lottery.storage;

import cn.xiwanzi.lottery.model.Award;
import cn.xiwanzi.lottery.model.LedgerEntry;
import cn.xiwanzi.lottery.model.LotteryType;
import cn.xiwanzi.lottery.model.PeriodState;
import cn.xiwanzi.lottery.model.PrizeTier;
import cn.xiwanzi.lottery.model.Ticket;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class StorageService {
    private final JavaPlugin plugin;
    private Connection connection;

    public StorageService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void init() {
        try {
            plugin.getDataFolder().mkdirs();
            Class.forName("org.sqlite.JDBC");
            File database = new File(plugin.getDataFolder(), "lottery.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("PRAGMA journal_mode=WAL");
                statement.executeUpdate("PRAGMA busy_timeout=5000");
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS periods (
                          type TEXT PRIMARY KEY,
                          period_id INTEGER NOT NULL,
                          next_draw_at INTEGER NOT NULL,
                          rollover REAL NOT NULL DEFAULT 0,
                          last_first_winner TEXT NOT NULL DEFAULT ''
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS tickets (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          type TEXT NOT NULL,
                          period_id INTEGER NOT NULL,
                          player_uuid TEXT NOT NULL,
                          player_name TEXT NOT NULL,
                          price REAL NOT NULL,
                          created_at INTEGER NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS awards (
                          type TEXT NOT NULL,
                          period_id INTEGER NOT NULL,
                          tier TEXT NOT NULL,
                          player_uuid TEXT NOT NULL,
                          player_name TEXT NOT NULL,
                          amount REAL NOT NULL,
                          created_at INTEGER NOT NULL,
                          PRIMARY KEY(type, period_id, tier, player_uuid)
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS ledger (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          type TEXT NOT NULL,
                          period_id INTEGER NOT NULL,
                          action TEXT NOT NULL,
                          player_uuid TEXT,
                          player_name TEXT,
                          amount REAL NOT NULL,
                          created_at INTEGER NOT NULL,
                          note TEXT NOT NULL DEFAULT ''
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS emails (
                          player_uuid TEXT PRIMARY KEY,
                          player_name TEXT NOT NULL,
                          email TEXT NOT NULL,
                          updated_at INTEGER NOT NULL
                        )
                        """);
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tickets_period ON tickets(type, period_id)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tickets_player ON tickets(type, period_id, player_uuid)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_awards_period ON awards(type, period_id)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ledger_period_action ON ledger(type, period_id, action)");
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize lottery storage.", ex);
        }
    }

    public synchronized void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to close database: " + ex.getMessage());
        }
    }

    public synchronized PeriodState getOrCreatePeriod(LotteryType type, long nextDrawAt) {
        Optional<PeriodState> existing = getPeriod(type);
        if (existing.isPresent()) {
            return existing.get();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO periods(type, period_id, next_draw_at, rollover, last_first_winner) VALUES(?, 1, ?, 0, '')")) {
            statement.setString(1, type.key());
            statement.setLong(2, nextDrawAt);
            statement.executeUpdate();
            return new PeriodState(type, 1, nextDrawAt, 0, "");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create period.", ex);
        }
    }

    public synchronized Optional<PeriodState> getPeriod(LotteryType type) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT period_id, next_draw_at, rollover, last_first_winner FROM periods WHERE type = ?")) {
            statement.setString(1, type.key());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PeriodState(
                        type,
                        result.getLong("period_id"),
                        result.getLong("next_draw_at"),
                        result.getDouble("rollover"),
                        result.getString("last_first_winner")
                ));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read period.", ex);
        }
    }

    public synchronized void setNextDrawAt(LotteryType type, long nextDrawAt) {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE periods SET next_draw_at = ? WHERE type = ?")) {
            statement.setLong(1, nextDrawAt);
            statement.setString(2, type.key());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to update next draw time.", ex);
        }
    }

    public synchronized void advancePeriod(LotteryType type, long nextDrawAt, double rollover, String lastFirstWinner) {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE periods SET period_id = period_id + 1, next_draw_at = ?, rollover = ?, last_first_winner = ? WHERE type = ?")) {
            statement.setLong(1, nextDrawAt);
            statement.setDouble(2, rollover);
            statement.setString(3, lastFirstWinner == null ? "" : lastFirstWinner);
            statement.setString(4, type.key());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to advance period.", ex);
        }
    }

    public synchronized void addRollover(LotteryType type, double amount) {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE periods SET rollover = rollover + ? WHERE type = ?")) {
            statement.setDouble(1, amount);
            statement.setString(2, type.key());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to add rollover.", ex);
        }
    }

    public synchronized void resetLottery(LotteryType type, long nextDrawAt, double clearedPool, UUID operatorUuid, String operatorName) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement deleteTickets = connection.prepareStatement("DELETE FROM tickets WHERE type = ?");
                 PreparedStatement deleteAwards = connection.prepareStatement("DELETE FROM awards WHERE type = ?");
                 PreparedStatement deleteLedger = connection.prepareStatement("DELETE FROM ledger WHERE type = ?");
                 PreparedStatement deletePeriod = connection.prepareStatement("DELETE FROM periods WHERE type = ?");
                 PreparedStatement insertPeriod = connection.prepareStatement(
                         "INSERT INTO periods(type, period_id, next_draw_at, rollover, last_first_winner) VALUES(?, 1, ?, 0, '')");
                 PreparedStatement insertLedger = connection.prepareStatement(
                         "INSERT INTO ledger(type, period_id, action, player_uuid, player_name, amount, created_at, note) VALUES(?, 1, 'RESET', ?, ?, ?, ?, ?)")) {
                deleteTickets.setString(1, type.key());
                deleteTickets.executeUpdate();

                deleteAwards.setString(1, type.key());
                deleteAwards.executeUpdate();

                deleteLedger.setString(1, type.key());
                deleteLedger.executeUpdate();

                deletePeriod.setString(1, type.key());
                deletePeriod.executeUpdate();

                insertPeriod.setString(1, type.key());
                insertPeriod.setLong(2, nextDrawAt);
                insertPeriod.executeUpdate();

                insertLedger.setString(1, type.key());
                insertLedger.setString(2, operatorUuid.toString());
                insertLedger.setString(3, operatorName);
                insertLedger.setDouble(4, clearedPool);
                insertLedger.setLong(5, System.currentTimeMillis());
                insertLedger.setString(6, "reset current lottery pool");
                insertLedger.executeUpdate();
            }
            connection.commit();
        } catch (SQLException ex) {
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                plugin.getLogger().warning("Failed to roll back reset transaction: " + rollbackEx.getMessage());
            }
            throw new IllegalStateException("Failed to reset lottery.", ex);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ex) {
                plugin.getLogger().warning("Failed to restore auto commit: " + ex.getMessage());
            }
        }
    }

    public synchronized void addTicket(LotteryType type, long periodId, UUID playerUuid, String playerName, double price) {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO tickets(type, period_id, player_uuid, player_name, price, created_at) VALUES(?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, type.key());
            statement.setLong(2, periodId);
            statement.setString(3, playerUuid.toString());
            statement.setString(4, playerName);
            statement.setDouble(5, price);
            statement.setLong(6, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to add ticket.", ex);
        }
    }

    public synchronized void addPurchasedTicket(LotteryType type, long periodId, UUID playerUuid, String playerName, double price) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement ticket = connection.prepareStatement(
                    "INSERT INTO tickets(type, period_id, player_uuid, player_name, price, created_at) VALUES(?, ?, ?, ?, ?, ?)");
                 PreparedStatement ledger = connection.prepareStatement(
                         "INSERT INTO ledger(type, period_id, action, player_uuid, player_name, amount, created_at, note) VALUES(?, ?, ?, ?, ?, ?, ?, ?)")) {
                ticket.setString(1, type.key());
                ticket.setLong(2, periodId);
                ticket.setString(3, playerUuid.toString());
                ticket.setString(4, playerName);
                ticket.setDouble(5, price);
                ticket.setLong(6, System.currentTimeMillis());
                ticket.executeUpdate();

                ledger.setString(1, type.key());
                ledger.setLong(2, periodId);
                ledger.setString(3, "PURCHASE");
                ledger.setString(4, playerUuid.toString());
                ledger.setString(5, playerName);
                ledger.setDouble(6, price);
                ledger.setLong(7, System.currentTimeMillis());
                ledger.setString(8, "ticket");
                ledger.executeUpdate();
            }
            connection.commit();
        } catch (SQLException ex) {
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                plugin.getLogger().warning("Failed to roll back purchase transaction: " + rollbackEx.getMessage());
            }
            throw new IllegalStateException("Failed to add purchased ticket.", ex);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ex) {
                plugin.getLogger().warning("Failed to restore auto commit: " + ex.getMessage());
            }
        }
    }

    public synchronized int countPlayerTickets(LotteryType type, long periodId, UUID playerUuid) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM tickets WHERE type = ? AND period_id = ? AND player_uuid = ?")) {
            statement.setString(1, type.key());
            statement.setLong(2, periodId);
            statement.setString(3, playerUuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to count tickets.", ex);
        }
    }

    public synchronized int countTickets(LotteryType type, long periodId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM tickets WHERE type = ? AND period_id = ?")) {
            statement.setString(1, type.key());
            statement.setLong(2, periodId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to count tickets.", ex);
        }
    }

    public synchronized List<Ticket> tickets(LotteryType type, long periodId) {
        List<Ticket> tickets = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, player_uuid, player_name, price FROM tickets WHERE type = ? AND period_id = ? ORDER BY id ASC")) {
            statement.setString(1, type.key());
            statement.setLong(2, periodId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    tickets.add(new Ticket(
                            result.getLong("id"),
                            type,
                            periodId,
                            UUID.fromString(result.getString("player_uuid")),
                            result.getString("player_name"),
                            result.getDouble("price")
                    ));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read tickets.", ex);
        }
        return tickets;
    }

    public synchronized double ticketPool(LotteryType type, long periodId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(SUM(price), 0) FROM tickets WHERE type = ? AND period_id = ?")) {
            statement.setString(1, type.key());
            statement.setLong(2, periodId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getDouble(1) : 0;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to sum ticket pool.", ex);
        }
    }

    public synchronized void recordLedger(LotteryType type, long periodId, String action, UUID playerUuid, String playerName, double amount, String note) {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ledger(type, period_id, action, player_uuid, player_name, amount, created_at, note) VALUES(?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, type.key());
            statement.setLong(2, periodId);
            statement.setString(3, action);
            statement.setString(4, playerUuid == null ? null : playerUuid.toString());
            statement.setString(5, playerName);
            statement.setDouble(6, amount);
            statement.setLong(7, System.currentTimeMillis());
            statement.setString(8, note == null ? "" : note);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to write ledger.", ex);
        }
    }

    public synchronized boolean hasLedger(LotteryType type, long periodId, String action, UUID playerUuid, String note) {
        String sql = playerUuid == null
                ? "SELECT 1 FROM ledger WHERE type = ? AND period_id = ? AND action = ? AND player_uuid IS NULL AND note = ? LIMIT 1"
                : "SELECT 1 FROM ledger WHERE type = ? AND period_id = ? AND action = ? AND player_uuid = ? AND note = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, type.key());
            statement.setLong(2, periodId);
            statement.setString(3, action);
            if (playerUuid == null) {
                statement.setString(4, note == null ? "" : note);
            } else {
                statement.setString(4, playerUuid.toString());
                statement.setString(5, note == null ? "" : note);
            }
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read ledger.", ex);
        }
    }

    public synchronized void saveAwards(List<Award> awards) {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO awards(type, period_id, tier, player_uuid, player_name, amount, created_at)
                VALUES(?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (Award award : awards) {
                statement.setString(1, award.type().key());
                statement.setLong(2, award.periodId());
                statement.setString(3, award.tier().key());
                statement.setString(4, award.playerUuid().toString());
                statement.setString(5, award.playerName());
                statement.setDouble(6, award.amount());
                statement.setLong(7, System.currentTimeMillis());
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to save awards.", ex);
        }
    }

    public synchronized List<Award> awards(LotteryType type, long periodId) {
        List<Award> awards = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT tier, player_uuid, player_name, amount FROM awards WHERE type = ? AND period_id = ? ORDER BY tier ASC, player_name ASC")) {
            statement.setString(1, type.key());
            statement.setLong(2, periodId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    awards.add(new Award(
                            type,
                            periodId,
                            PrizeTier.valueOf(result.getString("tier").toUpperCase()),
                            UUID.fromString(result.getString("player_uuid")),
                            result.getString("player_name"),
                            result.getDouble("amount")
                    ));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read awards.", ex);
        }
        return awards;
    }

    public synchronized List<LedgerEntry> playerLedger(UUID playerUuid, int limit) {
        List<LedgerEntry> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT type, period_id, action, player_name, amount, created_at, note
                FROM ledger
                WHERE player_uuid = ?
                ORDER BY id DESC
                LIMIT ?
                """)) {
            statement.setString(1, playerUuid.toString());
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    entries.add(ledgerEntry(result));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read player ledger.", ex);
        }
        return entries;
    }

    public synchronized List<LedgerEntry> periodLedger(LotteryType type, long periodId) {
        List<LedgerEntry> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT type, period_id, action, player_name, amount, created_at, note
                FROM ledger
                WHERE type = ? AND period_id = ?
                ORDER BY id ASC
                """)) {
            statement.setString(1, type.key());
            statement.setLong(2, periodId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    entries.add(ledgerEntry(result));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read period ledger.", ex);
        }
        return entries;
    }

    private LedgerEntry ledgerEntry(ResultSet result) throws SQLException {
        return new LedgerEntry(
                LotteryType.from(result.getString("type")).orElse(LotteryType.DAILY),
                result.getLong("period_id"),
                result.getString("action"),
                result.getString("player_name"),
                result.getDouble("amount"),
                result.getLong("created_at"),
                result.getString("note")
        );
    }

    public synchronized Optional<String> getEmail(UUID playerUuid) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT email FROM emails WHERE player_uuid = ?")) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString("email")) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read email.", ex);
        }
    }

    public synchronized void setEmail(UUID playerUuid, String playerName, String email) {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO emails(player_uuid, player_name, email, updated_at) VALUES(?, ?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET player_name = excluded.player_name, email = excluded.email, updated_at = excluded.updated_at
                """)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, playerName);
            statement.setString(3, email);
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to save email.", ex);
        }
    }

    public synchronized void clearEmail(UUID playerUuid) {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM emails WHERE player_uuid = ?")) {
            statement.setString(1, playerUuid.toString());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to clear email.", ex);
        }
    }
}
