package org.openclub.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.application.install
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Checks every response of the API tests against docs/contracts/openapi-v1.yaml (owner-approved 2026-11-28): the
 * status must be documented for the operation, and a JSON body must match the documented schema (missing or extra
 * fields, wrong types or enum values). Installed by [checkContract] in each test application; failures are collected
 * and fail the test class ([ContractCheckExtension]), so a test that never looks at a body still checks it.
 */
internal object ContractCheck {
    private val mapper = ObjectMapper()
    private val root: JsonNode by lazy {
        ObjectMapper(YAMLFactory()).readTree(File(requireNotNull(System.getProperty("openclub.contract")) { "openclub.contract" }))
    }
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    private val schemas = ConcurrentHashMap<String, JsonSchema>()
    val failures = ConcurrentLinkedQueue<String>()

    private class Operation(val template: String, val method: String, val pattern: Regex, val node: JsonNode)

    /** Literal paths before templated ones (`/v1/guardians/missing` before `/v1/guardians/{id}`). */
    private val operations: List<Operation> by lazy {
        root.get("paths").properties().flatMap { (template, item) ->
            item.properties().filter { it.key in setOf("get", "put", "post", "patch", "delete") }.map { (method, node) ->
                val pattern = Regex("^/api" + template.split('/').joinToString("/") {
                    if (it.startsWith("{")) "[^/]+" else Regex.escape(it)
                } + "$")
                Operation(template, method.uppercase(), pattern, node)
            }
        }.sortedBy { operation -> operation.template.count { it == '{' } }
    }

    private fun resolve(node: JsonNode): JsonNode {
        val ref = node.get("\$ref")?.asText() ?: return node
        return root.at(ref.removePrefix("#"))
    }

    fun check(method: String, path: String, status: Int, contentType: ContentType?, body: String) {
        val operation = operations.firstOrNull { it.method == method && it.pattern.matches(path) } ?: return
        val where = "$method ${operation.template} → $status"
        val response = operation.node.get("responses")?.get(status.toString())?.let(::resolve)
        if (response == null) {
            failures += "$where: status not documented"
            return
        }
        if (contentType?.withoutParameters() != ContentType.Application.Json) return
        val schemaNode = response.get("content")?.get("application/json")?.get("schema")
        if (schemaNode == null) {
            failures += "$where: JSON body, but no JSON schema documented"
            return
        }
        val schema = schemas.getOrPut(where) {
            // The schema with the components beside it, so `#/components/...` references resolve.
            val wrapper = (schemaNode.deepCopy<JsonNode>() as ObjectNode)
            wrapper.put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            wrapper.set<JsonNode>("components", root.get("components"))
            factory.getSchema(wrapper)
        }
        val problems = schema.validate(mapper.readTree(body))
        if (problems.isNotEmpty()) failures += "$where: " + problems.joinToString("; ") { it.message }
    }
}

private val ContractCheckPlugin = createApplicationPlugin("ContractCheck") {
    on(ResponseBodyReadyForSend) { call, content ->
        val text = content as? TextContent ?: return@on
        val status = text.status ?: call.response.status() ?: HttpStatusCode.OK
        ContractCheck.check(call.request.httpMethod.value, call.request.path(), status.value, text.contentType, text.text)
    }
}

/** Installs the contract check in a test application; call it before `module(...)`. */
internal fun Application.checkContract() = install(ContractCheckPlugin)

/** Fails a test class whose responses did not match the contract (registered for every test class). */
class ContractCheckExtension : AfterAllCallback {
    override fun afterAll(context: ExtensionContext) {
        val found = generateSequence { ContractCheck.failures.poll() }.toList().distinct()
        if (found.isNotEmpty()) throw AssertionError("Responses not matching docs/contracts/openapi-v1.yaml:\n" + found.joinToString("\n"))
    }
}
