package kotliquery

import kotliquery.action.*
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.math.BigDecimal
import java.net.URL
import java.sql.Array
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.Timestamp
import java.time.*
import java.util.*

/**
 * Database Session.
 */
open class Session(
        open val connection: Connection,
        open val returnGeneratedKeys: Boolean = true,
        open val autoGeneratedKeys: List<String> = listOf(),
        var transactional: Boolean = false) : AutoCloseable {

    override fun close() {
        transactional = false
        connection.close()
    }

    private val logger = LoggerFactory.getLogger(Session::class.java)

    private inline fun <reified T> PreparedStatement.setTypedParam(idx: Int, param: Parameter<T>) {
        if (param.value == null) {
            this.setNull(idx, param.sqlType())
        } else {
            setParam(idx, param.value)
        }
    }

    private fun PreparedStatement.setParam(idx: Int, v: Any?) {
        if (v == null) {
            this.setObject(idx, null)
        } else {
            when (v) {
                is String -> this.setString(idx, v)
                is Byte -> this.setByte(idx, v)
                is Boolean -> this.setBoolean(idx, v)
                is Int -> this.setInt(idx, v)
                is Long -> this.setLong(idx, v)
                is Short -> this.setShort(idx, v)
                is Double -> this.setDouble(idx, v)
                is Float -> this.setFloat(idx, v)
                is ZonedDateTime -> this.setTimestamp(idx, Timestamp(Date.from(v.toInstant()).time))
                is OffsetDateTime -> this.setTimestamp(idx, Timestamp(Date.from(v.toInstant()).time))
                is Instant -> this.setTimestamp(idx, Timestamp(Date.from(v).time))
                is LocalDateTime -> this.setTimestamp(idx, Timestamp(org.joda.time.LocalDateTime.parse(v.toString()).toDate().time))
                is LocalDate -> this.setDate(idx, java.sql.Date(org.joda.time.LocalDate.parse(v.toString()).toDate().time))
                is LocalTime -> this.setTime(idx, java.sql.Time(org.joda.time.LocalTime.parse(v.toString()).toDateTimeToday().millis))
                is org.joda.time.DateTime -> this.setTimestamp(idx, Timestamp(v.toDate().time))
                is org.joda.time.LocalDateTime -> this.setTimestamp(idx, Timestamp(v.toDate().time))
                is org.joda.time.LocalDate -> this.setDate(idx, java.sql.Date(v.toDate().time))
                is org.joda.time.LocalTime -> this.setTime(idx, java.sql.Time(v.toDateTimeToday().millis))
                is java.util.Date -> this.setTimestamp(idx, Timestamp(v.time))
                is java.sql.Timestamp -> this.setTimestamp(idx, v)
                is java.sql.Time -> this.setTime(idx, v)
                is java.sql.Date -> this.setTimestamp(idx, Timestamp(v.time))
                is java.sql.SQLXML -> this.setSQLXML(idx, v)
                is ByteArray -> this.setBytes(idx, v)
                is InputStream -> this.setBinaryStream(idx, v)
                is BigDecimal -> this.setBigDecimal(idx, v)
                is java.sql.Array -> this.setArray(idx, v)
                is URL -> this.setURL(idx, v)
                else -> this.setObject(idx, v)
            }
        }
    }

    fun createArrayOf(typeName: String, items: Collection<Any>): Array = connection.underlying.createArrayOf(typeName, items.toTypedArray())

    fun populateParams(query: Query, stmt: PreparedStatement): PreparedStatement {
        if (query.replacementMap.isNotEmpty()) {
            query.replacementMap.forEach { paramName, occurrences ->
                occurrences.forEach {
                    stmt.setTypedParam(it + 1, query.paramMap[paramName].param())
                }
            }
        } else {
            query.params.forEachIndexed { index, value ->
                stmt.setTypedParam(index + 1, value.param())
            }
        }

        return stmt
    }

    fun createPreparedStatement(query: Query): PreparedStatement {
        val stmt = if (returnGeneratedKeys) {
            if (connection.driverName == "oracle.jdbc.driver.OracleDriver") {
                connection.underlying.prepareStatement(query.cleanStatement, autoGeneratedKeys.toTypedArray())
            } else {
                connection.underlying.prepareStatement(query.cleanStatement, Statement.RETURN_GENERATED_KEYS)
            }
        } else {
            connection.underlying.prepareStatement(query.cleanStatement)
        }

        return populateParams(query, stmt)
    }

    private fun <A> rows(query: Query, extractor: (Row) -> A?): List<A> {
        return using(createPreparedStatement(query)) { stmt ->
            using(stmt.executeQuery()) { rs ->
                val rows = Row(rs).map { row -> extractor.invoke(row) }
                rows.filter { r -> r != null }.map { r -> r!! }.toList()
            }
        }
    }

    private fun rowsBatched(statement: String, params: Collection<Collection<Any?>>, namedParams: Collection<Map<String, Any?>>): List<Int> {
        return using(connection.underlying.prepareStatement(Query(statement).cleanStatement)) { stmt ->

            if (namedParams.isNotEmpty()) {
                val extracted = Query.extractNamedParamsIndexed(statement)
                namedParams.forEach { paramRow ->
                    extracted.forEach { paramName, occurrences ->
                        occurrences.forEach {
                            stmt.setTypedParam(it + 1, paramRow[paramName].param())
                        }
                    }
                    stmt.addBatch()
                }
            } else {
                params.forEach { paramsRow ->
                    paramsRow.forEachIndexed { idx, value ->
                        stmt.setTypedParam(idx + 1, value.param())
                    }
                    stmt.addBatch()
                }
            }
            stmt.executeBatch().toList()
        }
    }

    private fun warningForTransactionMode(): Unit {
        if (transactional) {
            logger.warn("Use TransactionalSession instead. The `tx` of `session.transaction { tx -> ... }`")
        }
    }

    fun <A> single(query: Query, extractor: (Row) -> A?): A? {
        warningForTransactionMode()
        val rs = rows(query, extractor)
        return if (rs.size > 0) rs.first() else null
    }

    fun <A> list(query: Query, extractor: (Row) -> A?): List<A> {
        warningForTransactionMode()
        return rows(query, extractor).toList()
    }

    fun batchPreparedNamedStatement(statement: String, params: Collection<Map<String, Any?>>): List<Int> {
        warningForTransactionMode()
        return rowsBatched(statement, emptyList(), params)
    }

    fun batchPreparedStatement(statement: String, params: Collection<Collection<Any?>>): List<Int> {
        warningForTransactionMode()
        return rowsBatched(statement, params, emptyList())
    }

    fun forEach(query: Query, operator: (Row) -> Unit): Unit {
        warningForTransactionMode()
        using(createPreparedStatement(query)) { stmt ->
            using(stmt.executeQuery()) { rs ->
                Row(rs).forEach { row -> operator.invoke(row) }
            }
        }
    }

    fun execute(query: Query): Boolean {
        warningForTransactionMode()
        return using(createPreparedStatement(query)) { stmt ->
            stmt.execute()
        }
    }

    fun update(query: Query): Int {
        warningForTransactionMode()
        return using(createPreparedStatement(query)) { stmt ->
            stmt.executeUpdate()
        }
    }

    fun updateAndReturnGeneratedKey(query: Query): Long? {
        warningForTransactionMode()
        return using(createPreparedStatement(query)) { stmt ->
            if (stmt.executeUpdate() > 0) {
                val rs = stmt.generatedKeys
                val hasNext = rs.next()
                if (!hasNext) {
                    logger.warn("Unexpectedly, Statement#getGeneratedKeys doesn't have any elements for " + query.statement)
                }
                rs.getLong(1)
            } else null
        }
    }

    fun run(action: ExecuteQueryAction): Boolean {
        return action.runWithSession(this)
    }

    fun run(action: UpdateQueryAction): Int {
        return action.runWithSession(this)
    }

    fun run(action: UpdateAndReturnGeneratedKeyQueryAction): Long? {
        return action.runWithSession(this)
    }

    fun <A> run(action: ListResultQueryAction<A>): List<A> {
        return action.runWithSession(this)
    }

    fun <A> run(action: NullableResultQueryAction<A>): A? {
        return action.runWithSession(this)
    }

    fun <A> transaction(operation: (TransactionalSession) -> A): A {
        try {
            connection.begin()
            transactional = true
            val tx = TransactionalSession(connection, returnGeneratedKeys, autoGeneratedKeys)
            val result: A = operation.invoke(tx)
            connection.commit()
            return result
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            transactional = false
        }
    }

}
