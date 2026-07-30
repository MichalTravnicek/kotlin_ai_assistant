package com.assistant.database

import com.assistant.routes.sqlRoutes
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlRoutesTest {

    private fun sqlField(body: String): String =
        Json.parseToJsonElement(body).jsonObject["sql"]?.jsonPrimitive?.content ?: ""

    private fun rowCount(body: String): Int =
        Json.parseToJsonElement(body).jsonObject["rowCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1

    private fun rows(body: String): List<List<String>> {
        val arr = Json.parseToJsonElement(body).jsonObject["rows"] ?: return emptyList()
        return arr.jsonArray.map { it.jsonArray.map { el -> el.jsonPrimitive.content } }
    }

    private fun testApp(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
        routing {
            val db = DatabaseManager()
            val interpreter =
                SqlPromptInterpreter({ db.getSchema() }, { table, col -> db.queryDistinctValues(table, col) })
            sqlRoutes(db, interpreter)
        }
        test()
    }

    @Test
    fun `show tables returns table list`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "show tables"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("TABLE_NAME"), "Response should contain TABLE_NAME column")
        assertEquals(3, rowCount(body))
    }

    @Test
    fun `show employees returns employees data`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "show employees"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertEquals(10, rowCount(body))
        assertTrue(body.contains("Jan Novak"), "Response should contain sample employee 'Jan Novak'")
    }

    @Test
    fun `find employees in Engineering returns filtered results`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "find employees in Engineering"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("department_id", ignoreCase = true), "SQL should filter by department")
        assertEquals(3, rowCount(body))
    }

    @Test
    fun `count products returns count`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "count products"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("COUNT(*)", ignoreCase = true), "SQL should use COUNT(*)")
        assertEquals(1, rowCount(body))
        assertTrue(rows(body).any() { it.contains("10") })
    }

    @Test
    fun `products with price greater than 900 returns products`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "products with price > 900"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("PRICE > 900", ignoreCase = true), "SQL should filter PRICE > 900")
        assertEquals(8, rowCount(body))
    }

    @Test
    fun `join employees and departments returns combined data`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "join employees and departments"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("JOIN", ignoreCase = true), "SQL should use JOIN")
        assertTrue(sql.contains("FROM EMPLOYEES", ignoreCase = true), "SQL should alias employee columns")
        assertTrue(sql.contains("DEPARTMENTS", ignoreCase = true), "SQL should alias departments columns")
        assertEquals(10, rowCount(body))
    }

    @Test
    fun `average salary by department returns aggregation`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "average salary by department"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("AVG("), "SQL should use AVG")
        assertTrue(sql.contains("group_name"), "SQL should have group_name alias")
        assertTrue(sql.contains("AVG_SALARY"), "SQL should have AVG_SALARY alias")
        assertEquals(5, rowCount(body))
        assertTrue(rows(body).any() { it.contains("Engineering") },"Result should contain Engineering department")
    }

    @Test
    fun `cheapest product returns single product with lowest price`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "cheapest product"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("ORDER BY price ASC") || sql.contains("ORDER BY PRICE ASC"), "SQL should sort by price ASC")
        assertEquals(1, rowCount(body))
        assertTrue(body.contains("Water Bottle 1L"), "Cheapest product should be 'Water Bottle 1L' (399 CZK)")
    }

    @Test
    fun `most expensive product returns highest priced product`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "most expensive product"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("ORDER BY price DESC") || sql.contains("ORDER BY PRICE DESC"), "SQL should sort by price DESC")
        assertEquals(1, rowCount(body))
        assertTrue(body.contains("Laptop Pro X1"), "Most expensive should be 'Laptop Pro X1' (45990 CZK)")
    }

    @Test
    fun `product with lowest stock returns lowest stock product`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "product with lowest stock"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("ORDER BY stock ASC") || sql.contains("ORDER BY STOCK ASC"), "SQL should sort by stock ASC")
        assertEquals(1, rowCount(body))
        assertTrue(body.contains("Standing Desk"), "Lowest stock should be 'Standing Desk' (4 units)")
    }

    @Test
    fun `employee with lowest salary returns lowest paid employee`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "employee with lowest salary"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("ORDER BY salary ASC") || sql.contains("ORDER BY SALARY ASC"), "SQL should sort by salary ASC")
        assertEquals(1, rowCount(body))
        assertTrue(body.contains("David Hora"), "Lowest salary employee should be 'David Hora' (51000 CZK)")
    }

    @Test
    fun `custom raw SQL executes passthrough`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "SELECT * FROM employees WHERE salary > 70000"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertEquals("SELECT * FROM employees WHERE salary > 70000", sql)
        assertTrue(body.contains("Lucie Kralova"), "Result should contain high earners")
        assertEquals(6, rowCount(body))
    }

    @Test
    fun `raw SQL execute via dedicated endpoint`() = testApp {
        val response = client.post("/sql/execute") {
            contentType(ContentType.Application.Json)
            setBody("""{"sql": "SELECT name, salary FROM employees ORDER BY salary DESC LIMIT 3"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertEquals(3, rowCount(body))
        assertTrue(body.contains("Lucie Kralova"), "Top earner should be Lucie Kralova (105000)")
    }

    @Test
    fun `describe table returns schema info`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "describe employees"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("INFORMATION_SCHEMA.COLUMNS"), "Describe should query INFORMATION_SCHEMA")
        assertTrue(sql.lowercase().contains("employees"), "Should describe employees table")
        assertTrue(rowCount(body) > 0)
    }

    @Test
    fun `help prompt returns available tables`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "help"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("employees", ignoreCase = true), "Help should list employees")
        assertTrue(body.contains("departments", ignoreCase = true), "Help should list departments")
        assertTrue(body.contains("products", ignoreCase = true), "Help should list products")
    }

    @Test
    fun `unknown prompt returns SHOW TABLES fallback`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "blargh blah blah"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(
            body.contains("SHOW TABLES") || body.contains("didn't understand"),
            "Unknown prompt should return fallback"
        )
    }

    @Test
    fun `show departments returns department data`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "show departments"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertEquals(5, rowCount(body))
        assertTrue(body.contains("Engineering"), "Response should contain Engineering department")
        assertTrue(body.contains("Prague"), "Response should contain Prague")
    }

    @Test
    fun `count employees returns count`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "count employees"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("COUNT(*)", ignoreCase = true), "SQL should use COUNT(*)")
        assertEquals(1, rowCount(body))
        assertTrue(rows(body).any() { it.contains("10") })
    }

    @Test
    fun `bare condition price greater than 500 returns filtered products`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "price > 5000"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("PRICE > 5000") || sql.contains("price > 5000"), "SQL should filter PRICE > 5000")
        assertEquals(4, rowCount(body))
    }

    @Test
    fun `sum price by category returns aggregation`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "sum price by category"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("SUM("), "SQL should use SUM")
        assertTrue(sql.contains("group_name"), "SQL should have group_name alias")
        assertTrue(sql.contains("SUM_PRICE"), "SQL should have SUM_PRICE alias")
        assertEquals(4, rowCount(body))
    }

    @Test
    fun `GET sql schema returns table definitions`() = testApp {
        val response = client.get("/sql/schema")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        println(body)
        assertTrue(body.contains("employees", ignoreCase = true), "Schema should include employees table")
        assertTrue(
            body.contains("departments", ignoreCase = true),
            "Schema should include departments table"
        )
        assertTrue(body.contains("products", ignoreCase = true), "Schema should include products table")
        assertTrue(body.contains("columns"), "Response should contain columns field")
    }

    @Test
    fun `GET sql prompts returns examples`() = testApp {
        val response = client.get("/sql/prompts")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("examples"), "Response should contain examples field")
        assertTrue(body.contains("show tables"), "Examples should include 'show tables'")
        assertTrue(body.contains("cheapest product"), "Examples should include 'cheapest product'")
    }

    @Test
    fun `cheapest crap returns I didnt understand`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "cheapest crap"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("didn't understand"), "Nonsense field should be rejected")
    }

    @Test
    fun `cheapest employee returns lowest paid employee`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "cheapest employee"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("ORDER BY salary ASC") || sql.contains("ORDER BY SALARY ASC"), "SQL should sort by salary ASC")
        assertEquals(1, rowCount(body))
        assertTrue(body.contains("David Hora"), "Lowest salary employee should be 'David Hora' (51000 CZK)")
    }

    @Test
    fun `average crap by department returns I didnt understand`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "average crap by department"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("didn't understand"), "Nonsense field should be rejected")
    }

    @Test
    fun `average price by category returns aggregation`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "average price by category"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("AVG("), "SQL should use AVG")
        assertTrue(sql.contains("group_name"), "SQL should have group_name alias")
        assertTrue(sql.contains("AVG_PRICE"), "SQL should have AVG_PRICE alias")
        assertEquals(4, rowCount(body))
    }

    @Test
    fun `product with id 1 returns matching product`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "product with id 1"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("WHERE"), "SQL should have WHERE clause")
        assertTrue(sql.contains("ID = 1") || sql.contains("id = 1"), "SQL should filter by ID = 1")
        assertEquals(1, rowCount(body))
        assertTrue(body.contains("Model X") || body.contains("Laptop"), "Should return product with id 1")
    }

    @Test
    fun `product with crap greater than 1 returns I didnt understand`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "product with crap > 1"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("didn't understand"), "Nonsense field should be rejected")
    }

    @Test
    fun `product with stock 7 returns product with stock 7`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "product with stock 7"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("STOCK = 7") || sql.contains("stock = 7"), "SQL should filter stock = 7")
        assertEquals(1, rowCount(body))
    }

    @Test
    fun `product where price greater than 1000 returns filtered products`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "product where price > 1000"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("PRICE > 1000") || sql.contains("price > 1000"), "SQL should filter PRICE > 1000")
        assertEquals(8, rowCount(body))
    }

    @Test
    fun `bare table name employees returns all employees`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "employees"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertEquals(10, rowCount(body))
        assertTrue(body.contains("Jan Novak"), "Should return all employees")
    }

    @Test
    fun `highest price returns most expensive product`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "highest price"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("ORDER BY price DESC") || sql.contains("ORDER BY PRICE DESC"), "SQL should sort by price DESC")
        assertEquals(1, rowCount(body))
        assertTrue(body.contains("Laptop Pro X1"), "Highest price should be 'Laptop Pro X1'")
    }

    @Test
    fun `join departments and products returns cross join`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "join departments and products"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("FROM DEPARTMENTS, PRODUCTS"), "No FK between departments and products should cross-join")
    }

    @Test
    fun `count products with price greater than 1000 returns count`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "count products in price > 1000"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("COUNT(*)", ignoreCase = true), "SQL should use COUNT(*)")
        assertTrue(sql.contains("WHERE", ignoreCase = true), "SQL should have WHERE clause")
        assertTrue(sql.contains("price", ignoreCase = true), "SQL should filter by price")
        assertEquals(1, rowCount(body))
    }

    @Test
    fun `products in category Electronics returns filtered products`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "products with category Electronics"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("LOWER(CATEGORY)"), "SQL should use LOWER for case-insensitive match")
        assertEquals(5, rowCount(body))
    }

    @Test
    fun `nonsense bare condition rejected`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "foo > 5"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("didn't understand"), "Unknown field in bare condition should be rejected")
    }

    @Test
    fun `find employees with where conjunction works`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "find employees where department_id = 1"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("DEPARTMENT_ID = 1") || sql.contains("department_id = 1"), "SQL should filter DEPARTMENT_ID = 1")
        assertEquals(3, rowCount(body))
    }

    @Test
    fun `min price returns cheapest product`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "min price"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("ORDER BY price ASC") || sql.contains("ORDER BY PRICE ASC"), "SQL should sort by price ASC")
        assertEquals(1, rowCount(body))
        assertTrue(body.contains("Water Bottle 1L"), "Min price should be 'Water Bottle 1L' (399 CZK)")
    }

    @Test
    fun `employee with lowest stock returns I didnt understand because stock not in employees`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "employee with lowest stock"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("didn't understand"), "stock not in employees should be rejected")
    }

    @Test
    fun `average salary by department via czech prumer`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "prumer salary by department"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("AVG("), "SQL should use AVG")
        assertTrue(sql.contains("group_name"), "SQL should have group_name alias")
        assertEquals(5, rowCount(body))
        assertTrue(body.contains("Engineering"), "Should contain Engineering department")
    }

    @Test
    fun `most expensive employee returns highest paid employee`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "most expensive employee"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertTrue(sql.contains("ORDER BY salary DESC") || sql.contains("ORDER BY SALARY DESC"), "SQL should sort by salary DESC")
        assertEquals(1, rowCount(body))
        assertTrue(body.contains("Lucie Kralova"), "Highest salary should be 'Lucie Kralova' (105000 CZK)")
    }

    @Test
    fun `raw SQL with semicolon ignored`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "select * from products where stock < 10"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val sql = sqlField(body)
        assertEquals("select * from products where stock < 10", sql.lowercase())
        assertEquals(3, rowCount(body))
        assertTrue(body.contains("Standing Desk"), "Should include low stock products")
    }
}
