package com.assistant.database

/**
 * Interprets natural language prompts and maps them to SQL queries
 * using keyword/template matching. Schema-aware — knows table and column names.
 *
 * Patterns handled:
 *   "show tables" → SHOW TABLES
 *   "show <table>" → SELECT * FROM <table>
 *   "find/count <thing> in/with <condition>" → filtered SELECT / COUNT
 *   "join <t1> and <t2>" → JOIN query
 *   "average/sum/min/max <field> by <group>" → aggregation
 *   "custom <raw SQL>" → raw passthrough
 */
class SqlPromptInterpreter(
    private val schemaProvider: () -> List<DatabaseManager.TableInfo>
) {
    data class Interpretation(
        val sql: String,
        val explanation: String,
        val isSelect: Boolean
    )

    private val tableNames by lazy { schemaProvider().map { it.name.lowercase() } }
    private val columnMap by lazy {
        schemaProvider().flatMap { table ->
            table.columns.map { it.name.lowercase() to (table.name to it.name) }
        }.toMap()
    }

    // Known column aliases for natural language matching
    private val fieldAliases = mapOf(
        "salary" to "salary", "wage" to "salary", "income" to "salary", "plati" to "salary",
        "name" to "name", "jmeno" to "name", "full name" to "name",
        "department" to "department_id", "oddeleni" to "department_id",
        "price" to "price", "cena" to "price", "cost" to "price",
        "stock" to "stock", "skladem" to "stock", "quantity" to "stock",
        "category" to "category", "kategorie" to "category",
        "location" to "location", "misto" to "location", "city" to "location",
        "hire date" to "hire_date", "nastup" to "hire_date", "datum" to "hire_date"
    )

    // Table aliases
    private val tableAliases = mapOf(
        "employee" to "employees", "employees" to "employees",
        "employee" to "employees", "zamestnanci" to "employees",
        "department" to "departments", "departments" to "departments",
        "oddeleni" to "departments",
        "product" to "products", "products" to "products",
        "produkty" to "products", "items" to "products", "zbozi" to "products"
    )

    // Department name mapping for WHERE clauses
    private val deptNames = mapOf(
        "engineering" to "Engineering", "engineering" to "Engineering",
        "marketing" to "Marketing", "sales" to "Sales",
        "hr" to "HR", "human resources" to "HR",
        "finance" to "Finance"
    )

    /**
     * Interprets a natural language prompt into SQL.
     */
    fun interpret(prompt: String): Interpretation {
        val clean = prompt.trim().lowercase().replace(Regex("\\s+"), " ")

        // 1. Raw custom SQL passthrough
        if (clean.startsWith("custom ")) {
            val sql = prompt.substringAfter("custom").trim()
            val isSelect = sql.trim().lowercase().startsWith("select") ||
                    sql.trim().lowercase().startsWith("show") ||
                    sql.trim().lowercase().startsWith("with")
            return Interpretation(sql, "Raw SQL passthrough", isSelect)
        }

        // 2. SHOW TABLES
        if (clean.matches(Regex("show\\s+tables|list\\s+tables|tables"))) {
            return Interpretation("SHOW TABLES", "Showing all available tables", true)
        }

        // 3. SHOW <table> / display <table> / all <table>
        val showMatch = Regex("(?:show|display|list|all|select|zobraz|vsechny)\\s+(.+?)(?:\\s+table)?$").find(clean)
        if (showMatch != null) {
            val tbl = resolveTable(showMatch.groupValues[1])
            if (tbl != null) {
                return Interpretation("SELECT * FROM $tbl", "Selecting all rows from $tbl", true)
            }
        }

        // 4. DESCRIBE / STRUCTURE of table
        val descMatch = Regex("(?:describe|structure|schema|info)\\s+(.+?)(?:\\s+table)?$").find(clean)
        if (descMatch != null) {
            val tbl = resolveTable(descMatch.groupValues[1])
            if (tbl != null) {
                return Interpretation(
                    "SELECT COLUMN_NAME AS column, TYPE_NAME AS type FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = '$tbl'",
                    "Showing structure of $tbl", true
                )
            }
        }

        // 5. HELP / examples
        if (clean.matches(Regex("help|prompts|examples|commands|napoveda|priklady"))) {
            return Interpretation(
                "",
                "Available examples: show tables, show employees, find employees in Engineering, count products, products with price > 50, average salary by department, join employees and departments",
                true
            )
        }

        // 6. COUNT queries
        val countMatch = Regex("(?:how many|count|pocet|kolik)\\s+(.+?)(?:\\s+in\\s+(.+))?$").find(clean)
        if (countMatch != null) {
            val what = countMatch.groupValues[1].trim()
            val condition = countMatch.groupValues[2].trim()
            val tbl = resolveTable(what) ?: if (what.endsWith("s")) resolveTable(what.dropLast(1)) else null
            if (tbl != null) {
                return if (condition.isNotEmpty()) {
                    val colCond = resolveCondition(condition, tbl)
                    Interpretation(
                        "SELECT COUNT(*) AS count FROM $tbl WHERE ${colCond.first}",
                        "Counting rows in $tbl where ${colCond.second}",
                        true
                    )
                } else {
                    Interpretation("SELECT COUNT(*) AS count FROM $tbl", "Counting all rows in $tbl", true)
                }
            }
        }

        // 7. FIND/SELECT with conditions: "find employees in Engineering" or "products with price > 50"
        val findMatch =
            Regex("(?:find|search|select|get|where|najdi|vyhledej|uka?z)\\s+(.+?)(?:\\s+(?:in|from|where|with|that|kde|s|v|ve|z)\\s+(.+))?$").find(
                clean
            )
        if (findMatch != null) {
            val target = findMatch.groupValues[1].trim()
            val condition = findMatch.groupValues[2].trim()

            // Try to match a table
            val tbl = resolveTable(target)
            if (tbl != null) {
                return if (condition.isNotEmpty()) {
                    val (colSql, colDesc) = resolveCondition(condition, tbl)
                    Interpretation(
                        "SELECT * FROM $tbl WHERE $colSql",
                        "Finding rows in $tbl where $colDesc",
                        true
                    )
                } else {
                    Interpretation("SELECT * FROM $tbl", "Selecting all rows from $tbl", true)
                }
            }
        }

        // 8. "<table> with/in <condition>" style: "employees in Engineering" or "products with price > 50"
        val simpleWhereMatch = Regex("^(\\w+)\\s+(?:in|from|v|z|with|s|kde)\\s+(.+)$").find(clean)
        if (simpleWhereMatch != null) {
            val tbl = resolveTable(simpleWhereMatch.groupValues[1])
            val condition = simpleWhereMatch.groupValues[2].trim()
            if (tbl != null) {
                val (colSql, colDesc) = resolveCondition(condition, tbl)
                return Interpretation(
                    "SELECT * FROM $tbl WHERE $colSql",
                    "Finding rows in $tbl where $colDesc",
                    true
                )
            }
        }

        // 9. JOIN: "join employees and departments" or "employees joined with departments"
        val joinMatch = Regex("(?:join|spoj)\\s+(\\w+)\\s+(?:and|with|a|s)\\s+(\\w+)").find(clean)
        if (joinMatch != null) {
            val t1 = resolveTable(joinMatch.groupValues[1])
            val t2 = resolveTable(joinMatch.groupValues[2])
            if (t1 != null && t2 != null) {
                // Infer join key — look for common FK pattern
                val fkCol = "${t1.dropLast(1)}_id"
                val fkCol2 = "${t2.dropLast(1)}_id"
                // employees.department_id → departments.id
                val joinSql = if (t1 == "employees" && t2 == "departments") {
                    "SELECT e.*, d.name AS department_name, d.location FROM employees e JOIN departments d ON e.department_id = d.id"
                } else if (t1 == "departments" && t2 == "employees") {
                    "SELECT e.*, d.name AS department_name, d.location FROM employees e JOIN departments d ON e.department_id = d.id"
                } else if (t1 == "products" && t2 == "departments") {
                    "SELECT * FROM products CROSS JOIN departments"
                } else {
                    "SELECT * FROM $t1 JOIN $t2 ON $t1.$fkCol = $t2.id"
                }
                return Interpretation(joinSql, "Joining $t1 and $t2", true)
            }
        }

        // 10. Aggregation: "average salary by department" / "sum price by category"
        val aggMatch =
            Regex("(average|avg|prumer|sum|soucet|total|min|max|minimum|maximum|nejmensi|nejvetsi|nejlevnejsi|nejdrazsi|cheapest|most|nejlevnej|nejdraz)\\s+(.+?)(?:\\s+(?:by|per|podle|dle)\\s+(.+))?$").find(
                clean
            )
        if (aggMatch != null) {
            val aggFunc = aggMatch.groupValues[1].lowercase()
            val aggField = resolveField(aggMatch.groupValues[2].trim())
            val groupBy = aggMatch.groupValues[3].trim()

            val fn = when {
                aggFunc in listOf("average", "avg", "prumer") -> "AVG"
                aggFunc in listOf("sum", "soucet", "total") -> "SUM"
                aggFunc in listOf("min", "minimum", "nejmensi", "nejlevnejsi", "cheapest", "nejlevnej") -> "MIN"
                aggFunc in listOf("max", "maximum", "nejvetsi", "nejdrazsi", "most", "nejdraz") -> "MAX"
                else -> "AVG"
            }

            if (groupBy.isNotEmpty()) {
                val gbField = resolveField(groupBy)
                // Determine the table based on the aggregation field
                val table = inferTable(aggField.second) ?: inferTable(gbField.second) ?: "employees"
                val (gTable, gCol) = gbField
                val actualGbCol = if (gCol == "department_id" && gTable == "employees" && table == "employees") {
                    "d.name"
                } else gCol

                val joinClause = if (gCol == "department_id" || gCol == "department") {
                    " JOIN departments d ON e.department_id = d.id"
                } else ""

                val prefix = if (joinClause.isNotEmpty()) "e." else ""
                val groupCol = if (gCol == "department_id" || gCol == "department") "d.name" else "$prefix$gCol"
                val aggCol = "$prefix${aggField.second}"

                return Interpretation(
                    "SELECT $groupCol AS group_name, $fn($aggCol) AS agg_value FROM ${table} e$joinClause GROUP BY $groupCol ORDER BY agg_value DESC",
                    "${aggFunc.replaceFirstChar { it.uppercase() }} $aggCol grouped by $groupCol",
                    true
                )
            } else {
                // Simple aggregation: find the cheapest/most expensive thing
                val table = inferTable(aggField.second) ?: "products"
                // If the resolved field isn't a real column, use a sensible default
                val columns = schemaProvider().flatMap { it.columns.map { c -> c.name.lowercase() } }
                val sortCol = if (aggField.second.lowercase() in columns) aggField.second else {
                    when (table) {
                        "products" -> "price"; "employees" -> "salary"; else -> aggField.second
                    }
                }
                val order = if (aggFunc in listOf(
                        "min", "minimum", "nejmensi", "nejlevnejsi",
                        "cheapest", "nejlevnej"
                    )
                ) "ASC" else "DESC"
                return Interpretation(
                    "SELECT * FROM $table ORDER BY $sortCol $order LIMIT 1",
                    "Finding row with ${if (order == "ASC") "lowest" else "highest"} $sortCol",
                    true
                )
            }
        }

        // 11. Simple WHERE condition without explicit SELECT: "price > 50" or "salary > 50000"
        val bareConditionMatch = Regex("^(\\w+)\\s*(>|<|>=|<=|=|!=|equals|is|like)\\s*(.+)$").find(clean)
        if (bareConditionMatch != null) {
            val field = bareConditionMatch.groupValues[1]
            val op = bareConditionMatch.groupValues[2]
            val value = bareConditionMatch.groupValues[3].trim()
            val resolved = resolveField(field)
            val table = inferTable(resolved.second) ?: "products"
            val sqlOp = when (op) {
                "equals", "is" -> "="
                "like" -> "LIKE"
                else -> op
            }
            val sqlVal = if (value.toDoubleOrNull() != null || value.toIntOrNull() != null) value else "'$value'"
            return Interpretation(
                "SELECT * FROM $table WHERE ${resolved.second} $sqlOp $sqlVal",
                "Finding rows where ${resolved.second} $op $value",
                true
            )
        }

        // 12. Fallback: show tables with an explanation
        return Interpretation(
            "SHOW TABLES",
            "I didn't understand that prompt. Showing tables. Try: show employees, find products with price > 50, average salary by department",
            true
        )
    }

    // -------- Private helpers --------

    private fun resolveTable(name: String): String? {
        val key = name.trim().lowercase().trimEnd('s').trimEnd(' ')
        val direct = tableAliases[key]
        if (direct != null) return direct
        // Try with 's' suffix
        val withS = tableAliases["${key}s"]
        if (withS != null) return withS
        // Direct match against actual table names
        return tableNames.firstOrNull { it.startsWith(key) || it == key }
    }

    private fun resolveField(name: String): Pair<String, String> {
        val key = name.trim().lowercase()
        val alias = fieldAliases[key]
        if (alias != null) {
            val table = inferTable(alias) ?: "employees"
            return table to alias
        }
        // Try partial match
        for ((k, v) in fieldAliases) {
            if (key.contains(k) || k.contains(key)) {
                val table = inferTable(v) ?: "employees"
                return table to v
            }
        }
        return "products" to name
    }

    private fun resolveCondition(condition: String, table: String): Pair<String, String> {
        val clean = condition.trim().lowercase()

        // Try "field > value" or "field = value"
        val opMatch = Regex("^(\\w+)\\s*(>|<|>=|<=|=|!=)\\s*(.+)$").find(clean)
        if (opMatch != null) {
            val field = resolveField(opMatch.groupValues[1].trim()).second
            val op = opMatch.groupValues[2]
            val value = opMatch.groupValues[3].trim()
            val sqlValue = if (value.toDoubleOrNull() != null || value.toIntOrNull() != null) {
                if (value.contains('.')) value else value
            } else {
                "'${value.replace("'", "''")}'"
            }
            return "$field $op $sqlValue" to "$field $op $value"
        }

        // Try "is value" or "equals value"
        val isMatch = Regex("^(?:is|equals|like|je|=)\\s+(.+)$").find(clean)
        if (isMatch != null) {
            val value = isMatch.groupValues[1].trim()
            // Check if this is a department name
            val dept = deptNames[value.lowercase()]
            if (dept != null && table == "employees") {
                return "department_id = (SELECT id FROM departments WHERE name = '$dept')" to "department = '$dept'"
            }
            return "$value = '$value'" to "$value = '$value'"
        }

        // Try plain value match — check if it's a department
        val simpleValue = deptNames[clean]
        if (simpleValue != null && table == "employees") {
            return "department_id = (SELECT id FROM departments WHERE name = '$simpleValue')" to "department = '$simpleValue'"
        }

        // Try numeric comparison like "> 50" or ">= 100"
        val numMatch = Regex("^(>|<|>=|<=)\\s*(\\d+(?:\\.\\d+)?)$").find(clean)
        if (numMatch != null) {
            val op = numMatch.groupValues[1]
            val value = numMatch.groupValues[2]
            return "? $op $value" to "$op $value (field needs explicit name)"
        }

        return "? = '${clean.replace("'", "''")}'" to "$clean"
    }

    private fun inferTable(field: String): String? {
        return when (field) {
            "department_id", "department", "name" -> "employees"
            "salary", "hire_date" -> "employees"
            "price", "stock", "category" -> "products"
            "location" -> "departments"
            else -> {
                // Check if any table has this column
                schemaProvider().firstOrNull { table ->
                    table.columns.any { it.name.lowercase() == field.lowercase() }
                }?.name
            }
        }
    }
}
