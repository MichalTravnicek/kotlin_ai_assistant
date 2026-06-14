package com.assistant.database

import com.assistant.routes.sqlRoutes
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlRoutesTest {

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
        assertTrue(body.contains("rowCount"), "Response should contain rowCount field")
    }

    @Test
    fun `show employees returns employees data`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "show employees"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Jan Novak"), "Response should contain sample employee 'Jan Novak'")
        assertTrue(body.contains("salary", ignoreCase = true), "Response should contain salary column")
        assertTrue(body.contains("rowCount"), "Response should contain rowCount field")
    }

    @Test
    fun `find employees in Engineering returns filtered results`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "find employees in Engineering"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("sql"), "Response should contain generated SQL")
        assertTrue(body.contains("department_id", ignoreCase = true), "SQL should filter by department")
        // Engineering employees should be returned (case-insensitive subquery)
        assertTrue(
            body.contains("Jan Novak") || body.contains("Petr Svoboda"),
            "Response should contain Engineering employees"
        )
    }

    @Test
    fun `count products returns count`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "count products"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("COUNT", ignoreCase = true), "SQL should use COUNT")
        assertTrue(body.contains("10"), "Response should show count of 10 products")
        assertTrue(body.contains("rowCount"), "Response should contain rowCount")
    }

    @Test
    fun `products with price greater than 50 returns products`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "products with price > 900"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("price", ignoreCase = true), "SQL should contain price column")
        assertTrue(body.contains("rowCount"), "Response should contain rowCount")
        assertTrue(body.contains("Laptop Pro X1"), "Response should contain a product")
    }

    @Test
    fun `join employees and departments returns combined data`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "join employees and departments"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("JOIN", ignoreCase = true), "SQL should use JOIN")
        assertTrue(body.contains("departments_name", ignoreCase = true), "Result should have departments_name column")
        assertTrue(body.contains("Prague", ignoreCase = true), "Result should contain location data")
        assertTrue(body.contains("rowCount"), "Response should contain rowCount")
    }

    @Test
    fun `average salary by department returns aggregation`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "average salary by department"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("AVG", ignoreCase = true), "SQL should use AVG")
        assertTrue(body.contains("group_name", ignoreCase = true), "Result should have group_name column")
        assertTrue(body.contains("agg_value", ignoreCase = true), "Result should have agg_value column")
        assertTrue(body.contains("Engineering"), "Result should contain Engineering department")
        assertTrue(body.contains("rowCount"), "Response should contain rowCount")
    }

    @Test
    fun `cheapest product returns single product with lowest price`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "cheapest product"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("price", ignoreCase = true), "SQL should sort by price")
        assertTrue(body.contains("Water Bottle 1L"), "Cheapest product should be 'Water Bottle 1L' (399 CZK)")
        assertTrue(body.contains("rowCount"), "Response should contain rowCount")
    }

    @Test
    fun `most expensive product returns highest priced product`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "most expensive product"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("price", ignoreCase = true), "SQL should sort by price")
        assertTrue(body.contains("DESC", ignoreCase = true), "Should use descending order")
        assertTrue(body.contains("Laptop Pro X1"), "Most expensive should be 'Laptop Pro X1' (45990 CZK)")
        assertTrue(body.contains("rowCount"), "Response should contain rowCount")
    }

    @Test
    fun `product with lowest stock returns lowest stock product`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "product with lowest stock"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        System.err.println(body)
        assertTrue(body.contains("stock", ignoreCase = true), "SQL should sort by stock")
        assertTrue(body.contains("Standing Desk"), "Lowest stock should be 'Standing Desk' (4 units)")
        assertTrue(body.contains("\"rowCount\": 1"), "Response should contain rowCount 1")
    }

    @Test
    fun `employee with lowest salary returns lowest paid employee`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "employee with lowest salary"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("salary", ignoreCase = true), "SQL should sort by salary")
        assertTrue(body.contains("David Hora"), "Lowest salary employee should be 'David Hora' (51000 CZK)")
        assertTrue(body.contains("rowCount"), "Response should contain rowCount")
    }

    @Test
    fun `custom raw SQL executes passthrough`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "SELECT * FROM employees WHERE salary > 70000"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("SELECT"), "Response should contain the raw SQL")
        assertTrue(body.contains("salary", ignoreCase = true), "Result should contain salary column")
        assertTrue(body.contains("rowCount"), "Response should contain rowCount")
    }

    @Test
    fun `raw SQL execute via dedicated endpoint`() = testApp {
        val response = client.post("/sql/execute") {
            contentType(ContentType.Application.Json)
            setBody("""{"sql": "SELECT name, salary FROM employees ORDER BY salary DESC LIMIT 3"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Lucie Kralova"), "Top earner should be Lucie Kralova (105000)")
        assertTrue(body.contains("rowCount"), "Response should contain rowCount")
    }

    @Test
    fun `describe table returns schema info`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "describe employees"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("DATA_TYPE", ignoreCase = true), "Describe should show DATA_TYPE")
        assertTrue(body.contains("COLUMN_NAME", ignoreCase = true), "Describe should show COLUMN_NAME")
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
        assertTrue(body.contains("Engineering"), "Response should contain Engineering department")
        assertTrue(body.contains("Prague"), "Response should contain Prague")
        assertTrue(body.contains("rowCount"), "Response should contain rowCount")
    }

    @Test
    fun `count employees returns count`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "count employees"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("COUNT", ignoreCase = true), "SQL should use COUNT")
        assertTrue(body.contains("10"), "Response should show count of 10 employees")
        assertTrue(body.contains("rowCount"), "Response should contain rowCount")
    }

    @Test
    fun `bare condition price greater than 500 returns filtered products`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "price > 5000"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("price", ignoreCase = true), "SQL should contain price")
        assertTrue(body.contains("5000"), "Should filter by price > 5000")
        assertTrue(body.contains("rowCount"), "Response should contain rowCount")
    }

    @Test
    fun `sum price by category returns aggregation`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "sum price by category"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("SUM", ignoreCase = true), "SQL should use SUM")
        assertTrue(body.contains("group_name", ignoreCase = true), "Result should have group_name column")
        assertTrue(body.contains("agg_value", ignoreCase = true), "Result should have agg_value column")
        assertTrue(body.contains("rowCount"), "Response should contain rowCount")
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
}
