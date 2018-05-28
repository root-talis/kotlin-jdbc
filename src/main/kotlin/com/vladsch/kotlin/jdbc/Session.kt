package com.vladsch.kotlin.jdbc

import com.vladsch.boxed.json.MutableJsArray
import com.vladsch.boxed.json.MutableJsObject
import org.slf4j.LoggerFactory
import java.sql.CallableStatement
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import javax.json.JsonArray
import javax.json.JsonObject
import javax.sql.DataSource

open class Session(
    val connection: Connection,
    val autoGeneratedKeys: List<String> = listOf()
) : AutoCloseable {

    companion object {
        private val logger = LoggerFactory.getLogger(Session::class.java)

        var defaultDataSource: (() -> DataSource)? = null
    }

    override fun close() {
        connection.close()
    }

    fun prepare(query: SqlQuery, returnGeneratedKeys: Boolean = false): PreparedStatement {
        if (query is SqlCall) {
            val stmt = connection.underlying.prepareCall(query.cleanStatement)
            query.populateParams(stmt)
            return stmt
        } else {
            val stmt = if (returnGeneratedKeys) {
                if (connection.driverName == "oracle.jdbc.driver.OracleDriver") {
                    connection.underlying.prepareStatement(query.cleanStatement, autoGeneratedKeys.toTypedArray())
                } else {
                    connection.underlying.prepareStatement(query.cleanStatement, Statement.RETURN_GENERATED_KEYS)
                }
            } else {
                connection.underlying.prepareStatement(query.cleanStatement)
            }

            query.populateParams(stmt)
            return stmt
        }
    }

    inline fun <A> query(query: SqlQuery, crossinline consumer: (ResultSet) -> A): A {
        return using(prepare(query)) { stmt ->
            using(stmt.executeQuery()) { rs ->
                consumer.invoke(rs)
            }
        }
    }

    inline fun <A> executeWithKeys(query: SqlQuery, crossinline consumer: (PreparedStatement) -> A): A? {
        return using(prepare(query, true)) { stmt ->
            if (stmt.execute()) {
                consumer.invoke(stmt)
            } else {
                null
            }
        }
    }

    inline fun <A> execute(query: SqlQuery, crossinline consumer: (PreparedStatement) -> A): A? {
        return using(prepare(query)) { stmt ->
            if (stmt.execute()) {
                consumer.invoke(stmt)
            } else {
                null
            }
        }
    }

    inline fun <A> updateWithKeys(query: SqlQuery, crossinline consumer: (PreparedStatement) -> A): A? {
        return using(prepare(query, true)) { stmt ->
            if (stmt.executeUpdate() > 0) {
                consumer.invoke(stmt)
            } else {
                null
            }
        }
    }

    inline fun <A> update(query: SqlQuery, crossinline consumer: (PreparedStatement) -> A): A? {
        return using(prepare(query)) { stmt ->
            if (stmt.executeUpdate() > 0) {
                consumer.invoke(stmt)
            } else {
                null
            }
        }
    }

    fun <A> list(query: SqlQuery, extractor:  (Row)-> A): List<A> {
        return query(query) { rs ->
            val rows = ArrayList<A>()
            Rows(rs).forEach {
                val row = extractor.invoke(it)
                rows.add(row)
            }
            rows
        }
    }

    fun jsonArray(query: SqlQuery, extractor: (Row) -> JsonObject): JsonArray {
        return query(query) { rs ->
            val rows = MutableJsArray()
            Rows(rs).forEach {
                val row = extractor.invoke(it)
                rows.add(row)
            }
            rows
        }
    }

    fun count(query: SqlQuery): Int {
        return query(query) { rs ->
            var rows = 0;
            Rows(rs).forEach {
                rows++;
            }
            rows
        }
    }

    fun <A> first(query: SqlQuery, extractor: (Row) -> A): A? {
        return query(query) { rs ->
            if (rs.next()) {
                extractor.invoke(Row(rs))
            } else {
                null
            }
        }
    }

    fun <K, A> hashMap(query: SqlQuery, keyExtractor: (Row) -> K, extractor: (Row) -> A): Map<K, A> {
        return query(query) { rs ->
            val rowMap = HashMap<K, A>()
            Rows(rs).forEach { row ->
                val value = extractor.invoke(row)
                rowMap.put(keyExtractor.invoke(row), value)
            }
            rowMap;
        }
    }

    fun jsonObject(query: SqlQuery, keyExtractor: (Row) -> String, extractor: (Row) -> JsonObject): JsonObject {
        return query(query) { rs ->
            val rowMap = MutableJsObject()
            Rows(rs).forEach { row ->
                val value = extractor.invoke(row)
                rowMap.put(keyExtractor.invoke(row), value)
            }
            rowMap;
        }
    }

    fun forEach(query: SqlQuery, operator: (Row) -> Unit): Unit {
        return query(query) { rs ->
            Rows(rs).forEach { row ->
                operator.invoke(row)
            }
        }
    }

    fun forEach(query: SqlCall, stmtProc: (CallableStatement) -> Unit, operator: (rs: ResultSet, index: Int) -> Unit): Unit {
        execute(query) { stmt ->
            try {
                var results = stmt.execute();
                stmtProc.invoke(stmt as CallableStatement)

                var rsIndex = 0
                while (results) {
                    val rs = stmt.resultSet
                    try {
                        operator.invoke(rs, rsIndex++)
                    } finally {
                        rs.close()
                    }
                    results = stmt.moreResults
                }
            } finally {
                stmt.close()
            }
        }
    }

    fun execute(query: SqlQuery): Boolean {
        return using(prepare(query)) { stmt ->
            stmt.execute()
        }
    }

    fun update(query: SqlQuery): Int {
        return using(prepare(query)) { stmt ->
            stmt.executeUpdate()
        }
    }

    fun updateGetLongId(query: SqlQuery): Long? {
        return updateWithKeys(query) { stmt ->
            val rs = stmt.generatedKeys
            if (rs.next()) rs.getLong(1)
            else null
        }
    }

    fun updateGetId(query: SqlQuery): Int? {
        return updateWithKeys(query) { stmt ->
            val rs = stmt.generatedKeys
            if (rs.next()) rs.getInt(1)
            else null
        }
    }

    fun <A> updateGetKey(query: SqlQuery, extractor: (Row) -> A): A? {
        return updateWithKeys(query) { stmt ->
            val rs = stmt.generatedKeys
            if (rs.next()) extractor.invoke(Row(rs))
            else null
        }
    }

    fun updateGetLongIds(query: SqlQuery): List<Long>? {
        return updateWithKeys(query) { stmt ->
            val keys = ArrayList<Long>()
            val rs = stmt.generatedKeys
            while (rs.next()) {
                val id = rs.getLong(1)
                if (!rs.wasNull()) keys.add(id)
            }
            keys
        }
    }

    fun updateGetIds(query: SqlQuery): List<Int>? {
        return updateWithKeys(query) { stmt ->
            val keys = ArrayList<Int>()
            val rs = stmt.generatedKeys
            while (rs.next()) {
                val id = rs.getInt(1)
                if (!rs.wasNull()) keys.add(id)
            }
            keys
        }
    }

    fun <A> updateGetKeys(query: SqlQuery, extractor: (Row) -> A): List<A>? {
        return updateWithKeys(query) { stmt ->
            val keys = ArrayList<A>()
            val rows = Rows(stmt.generatedKeys)
            rows.forEach { row ->
                val value = extractor.invoke(row)
                keys.add(value)
            }
            keys
        }
    }

    fun <A> transaction(operation: (Transaction) -> A): A {
        try {
            connection.begin()

            val tx = Transaction(connection, autoGeneratedKeys)
            val result: A = operation.invoke(tx)

            if (!connection.autoCommit) {
                connection.commit()
            }
            return result
        } catch (e: Exception) {
            if (!connection.autoCommit) {
                connection.rollback()
            }
            throw e
        } finally {
            if (!connection.autoCommit) {
                connection.commit()
            }
        }
    }
}
