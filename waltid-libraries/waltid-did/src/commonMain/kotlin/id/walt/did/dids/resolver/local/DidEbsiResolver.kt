package id.walt.did.dids.resolver.local

import id.walt.crypto.keys.Key
import id.walt.crypto.keys.jwk.JWKKey
import id.walt.did.dids.document.DidDocument
import id.walt.did.dids.document.DidEbsiDocument
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import love.forte.plugin.suspendtrans.annotation.JsPromise
import love.forte.plugin.suspendtrans.annotation.JvmAsync
import love.forte.plugin.suspendtrans.annotation.JvmBlocking
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

@OptIn(ExperimentalJsExport::class)
@JsExport
class DidEbsiResolver(
    private val client: HttpClient,
) : LocalResolverMethod("ebsi") {

    val httpLogging = false
    private val didRegistryUrlBaseURL = "https://api-conformance.ebsi.eu/did-registry/v5/identifiers/"
    private val json = Json { ignoreUnknownKeys = true }

    @JvmBlocking
    @JvmAsync
    @JsPromise
    @JsExport.Ignore
    override suspend fun resolve(did: String): Result<DidDocument> = runCatching {
        resolveDid(did)
    }

    private suspend fun resolveDid(did: String): DidDocument {
        val url = didRegistryUrlBaseURL + did
        return client.get(url).bodyAsText().let {
            DidDocument(
                DidEbsiDocument(
                    DidDocument(
                        jsonObject = Json.parseToJsonElement(it).jsonObject
                    )
                ).toMap()
            )
        }
    }

    @JvmBlocking
    @JvmAsync
    @JsPromise
    @JsExport.Ignore
    override suspend fun resolveToKey(did: String): Result<Key> {
        // For backward compatibility, prioritize secp256r1 (P-256) keys
        val didDocumentResult = resolve(did)
        if (didDocumentResult.isFailure) return Result.failure(didDocumentResult.exceptionOrNull()!!)

        val publicKeyJwks = didDocumentResult.getOrNull()!!["verificationMethod"]!!.jsonArray.map {
            runCatching {
                val verificationMethod = it.jsonObject
                val publicKeyJwk = verificationMethod["publicKeyJwk"]!!.jsonObject
                DidWebResolver.json.encodeToString(publicKeyJwk)
            }
        }.filter { it.isSuccess }.map { it.getOrThrow() }

        return tryConvertAnyPublicKeyJwkToKey(publicKeyJwks)
    }
    
    @JvmBlocking
    @JvmAsync
    @JsPromise
    @JsExport.Ignore
    override suspend fun resolveToKeys(did: String): Result<Set<Key>> {
        val didDocumentResult = resolve(did)
        if (didDocumentResult.isFailure) return Result.failure(didDocumentResult.exceptionOrNull()!!)

        val publicKeyJwks = didDocumentResult.getOrNull()!!["verificationMethod"]!!.jsonArray.map {
            runCatching {
                val verificationMethod = it.jsonObject
                val publicKeyJwk = verificationMethod["publicKeyJwk"]!!.jsonObject
                DidWebResolver.json.encodeToString(publicKeyJwk)
            }
        }.filter { it.isSuccess }.map { it.getOrThrow() }

        return tryConvertPublicKeyJwksToKeys(publicKeyJwks)
    }

    /*
    Note from Christos:
    There exist cases of EBSI DID Documents that only have secp256k1. There is nothing invalid
    in not having a secp256r1 key. Hence, this function was changed to prioritize secp256r1 keys,
    but not to return a failure result otherwise.
    * NOTE:
    * */
    @JvmBlocking
    @JvmAsync
    @JsPromise
    @JsExport.Ignore
    suspend fun tryConvertAnyPublicKeyJwkToKey(publicKeyJwks: List<String>): Result<JWKKey> {
        publicKeyJwks.forEach { publicKeyJwk ->
            val result = JWKKey.importJWK(publicKeyJwk)
            if (result.isSuccess && publicKeyJwk.contains("P-256")) return result
        }
        return JWKKey.importJWK(publicKeyJwks.first())
    }
    
    @JvmBlocking
    @JvmAsync
    @JsPromise
    @JsExport.Ignore
    suspend fun tryConvertPublicKeyJwksToKeys(publicKeyJwks: List<String>): Result<Set<JWKKey>> {
        val keys = mutableSetOf<JWKKey>()
        
        for (publicKeyJwk in publicKeyJwks) {
            val result = JWKKey.importJWK(publicKeyJwk)
            if (result.isSuccess) {
                keys.add(result.getOrThrow())
            }
        }
        
        return if (keys.isNotEmpty()) {
            Result.success(keys)
        } else {
            Result.failure(NoSuchElementException("No keys could be imported from the DID document"))
        }
    }
}
