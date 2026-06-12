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
            val interpreter = SqlPromptInterpreter { db.getSchema() }
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
        assertTrue(body.contains("TABLE_NAME"), "Response should contain column header")
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
        assertTrue(body.contains("department_id"), "SQL should filter by department")
        assertTrue(body.contains("explanation"), "Response should contain explanation")
        // Engineering employees should be returned
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
            setBody("""{"prompt": "products with price > 50"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("price", ignoreCase = true), "SQL should contain price column")
        assertTrue(body.contains("rowCount"), "Response should contain rowCount")
        // All products are priced > 50 so we should get all 10
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
        assertTrue(body.contains("department_name", ignoreCase = true), "Result should have department_name column")
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
    fun `custom raw SQL executes passthrough`() = testApp {
        val response = client.post("/sql/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "custom SELECT * FROM employees WHERE salary > 50000"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("SELECT"), "Response should contain the raw SQL")
        assertTrue(body.contains("salary", ignoreCase = true), "Result should contain salary column")
        assertTrue(body.contains("rowCount"), "Response should contain rowCount")
    }

    @Test
    fun `GET sql schema returns table definitions`() = testApp {
        val response = client.get("/sql/schema")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("employees", ignoreCase = true), "Schema should include employees table")
        assertTrue(body.contains("departments", ignoreCase = true), "Schema should include departments table")
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
