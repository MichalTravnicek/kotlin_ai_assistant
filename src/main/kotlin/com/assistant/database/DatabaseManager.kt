package com.assistant.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * Manages an embedded H2 database with auto-seeded schema and sample data.
 * Provides connection pooling via HikariCP and safe query execution.
 */
class DatabaseManager(dbPath: String = "mem:assistant_db") {

    private val log = LoggerFactory.getLogger("com.assistant.database.DatabaseManager")
    private val dataSource: DataSource

    /** Describes a column in the schema. */
    data class ColumnInfo(val name: String, val type: String)

    /** Describes a table in the schema. */
    data class TableInfo(val name: String, val columns: List<ColumnInfo>)

    init {
        log.info("Initializing H2 database: $dbPath")

        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:h2:$dbPath;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
            driverClassName = "org.h2.Driver"
            username = "sa"
            password = ""
            maximumPoolSize = 4
            minimumIdle = 1
            idleTimeout = 15_000
            connectionTimeout = 5_000
        }
        dataSource = HikariDataSource(config)
        seedSchema()
        seedData()
        log.info("Database ready — tables: employees, departments, products")
    }

    /** Returns the schema as a list of table definitions. */
    fun getSchema(): List<TableInfo> {
        val tables = query("SHOW TABLES") { rs ->
            val name = rs.getString("TABLE_NAME")
            name
        }
        return tables.mapNotNull { tableName ->
            val columns = query(
                "SELECT COLUMN_NAME, DATA_TYPE AS TYPE_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ?",
                tableName
            ) { rs -> ColumnInfo(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME")) }
            if (columns.isNotEmpty()) TableInfo(tableName, columns) else null
        }
    }

    /** Returns example prompts for users to try. */
    fun examplePrompts(): List<String> = listOf(
        "show tables",
        "show employees",
        "find employees in Engineering",
        "count products",
        "products with price > 900",
        "join employees and departments",
        "average salary by department",
        "cheapest product",
        "employee with lowest salary",
        "SELECT * FROM employees WHERE salary > 70000"
    )

    /**
     * Executes an SQL query and returns the results.
     * @param sql the SQL to execute
     * @return a triple of (columnNames, rows, rowCount)
     */
    fun executeQuery(sql: String): Triple<List<String>, List<List<String>>, Int> {
        val trimmed = sql.trim().trimEnd(';')
        dataSource.connection.use { conn ->
            conn.prepareStatement(trimmed).use { stmt ->
                stmt.executeQuery().use { rs ->
                    val meta = rs.metaData
                    val colCount = meta.columnCount
                    val columns = (1..colCount).map { meta.getColumnLabel(it) }
                    val rows = mutableListOf<List<String>>()
                    while (rs.next()) {
                        rows.add((1..colCount).map { rs.getString(it) ?: "NULL" })
                    }
                    return Triple(columns, rows.toList(), rows.size)
                }
            }
        }
    }

    /**
     * Executes an update (INSERT, UPDATE, DELETE, DDL) and returns affected rows.
     */
    fun executeUpdate(sql: String): Int {
        val trimmed = sql.trim().trimEnd(';')
        log.info("Executing update: {}", trimmed.take(120))
        return dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(trimmed)
            }
        }
    }

    /** Query distinct text values from a column — used by SqlPromptInterpreter for dynamic value matching. */
    fun queryDistinctValues(table: String, column: String): List<String> {
        return query("SELECT DISTINCT $column FROM $table WHERE $column IS NOT NULL") { rs -> rs.getString(1) }
    }

    // ---------- private helpers ----------

    private fun <T> query(sql: String, vararg params: Any?, mapper: (java.sql.ResultSet) -> T): MutableList<T> {
        val results = mutableListOf<T>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                params.forEachIndexed { idx, param ->
                    stmt.setObject(idx + 1, param)
                }
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        results.add(mapper(rs))
                    }
                }
            }
        }
        return results
    }

    private fun seedSchema() {
        executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS departments (
                id INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                location VARCHAR(100)
            )
        """.trimIndent()
        )
        executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS employees (
                id INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                department_id INT,
                salary DECIMAL(10,2),
                hire_date DATE,
                FOREIGN KEY (department_id) REFERENCES departments(id)
            )
        """.trimIndent()
        )
        executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS products (
                id INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                category VARCHAR(50),
                price DECIMAL(10,2),
                stock INT
            )
        """.trimIndent()
        )
    }

    private fun seedData() {
        val deptCount = query("SELECT COUNT(*) AS c FROM departments") { it.getInt("c") }.firstOrNull() ?: 0
        if (deptCount > 0) return  // already seeded

        log.info("Seeding sample data...")

        executeUpdate(
            """
            INSERT INTO departments (name, location) VALUES
            ('Engineering', 'Prague'),
            ('Marketing', 'Brno'),
            ('Sales', 'Ostrava'),
            ('HR', 'Prague'),
            ('Finance', 'Brno')
        """.trimIndent()
        )

        executeUpdate(
            """
            INSERT INTO employees (name, department_id, salary, hire_date) VALUES
            ('Jan Novak', 1, 85000.00, '2021-03-15'),
            ('Petr Svoboda', 1, 92000.00, '2020-06-01'),
            ('Eva Dvorakova', 2, 65000.00, '2022-01-10'),
            ('Marie Prochazkova', 3, 55000.00, '2023-09-20'),
            ('Tomas Marek', 4, 60000.00, '2021-11-05'),
            ('Lucie Kralova', 1, 105000.00, '2019-04-22'),
            ('Jakub Vesely', 2, 72000.00, '2020-08-17'),
            ('Anna Cerna', 5, 78000.00, '2022-05-30'),
            ('David Hora', 3, 51000.00, '2024-01-15'),
            ('Katerina Ruzicka', 5, 95000.00, '2018-07-01')
        """.trimIndent()
        )

        executeUpdate(
            """
            INSERT INTO products (name, category, price, stock) VALUES
            ('Laptop Pro X1', 'Electronics', 45990.00, 15),
            ('Wireless Mouse', 'Electronics', 899.00, 42),
            ('Mechanical Keyboard', 'Electronics', 2490.00, 28),
            ('Office Chair Pro', 'Furniture', 8990.00, 7),
            ('Standing Desk', 'Furniture', 12990.00, 4),
            ('Monitor 27" 4K', 'Electronics', 15990.00, 11),
            ('Noise Cancelling Headphones', 'Electronics', 4990.00, 19),
            ('Coffee Machine', 'Appliances', 3490.00, 6),
            ('Water Bottle 1L', 'Accessories', 399.00, 100),
            ('Backpack Urban', 'Accessories', 1990.00, 23)
        """.trimIndent()
        )

        log.info("Sample data seeded: 5 departments, 10 employees, 10 products")
    }
}
