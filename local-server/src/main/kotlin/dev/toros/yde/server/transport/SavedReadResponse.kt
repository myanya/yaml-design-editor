package dev.toros.yde.server.transport

import dev.toros.yde.app.service.*
import dev.toros.yde.server.auth.LocalAccessFailure
import dev.toros.yde.server.protocol.externalJson
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** API と Web の保存版応答。能力・量・応答直前の認証と失敗の表現を共用する。 */
internal suspend fun ApplicationCall.respondSavedRead(reads: WorkspaceReads, responseBytes: Int = 4 * 1024 * 1024, query: suspend () -> ReadQuery) {
    try {
        val grant = requireLocalAccess(ConnectionCapability.READ)
        val result = reads.read(grant.caller, query())
        val body = externalJson(mapOf("workspaceId" to result.workspaceId, "epoch" to result.epoch,
            "revision" to result.revision, "data" to result.data)).toString()
        if (body.toByteArray(Charsets.UTF_8).size > responseBytes) throw ReadFailure(ReadFailureCode.LIMIT_EXCEEDED, "応答が大きすぎます。対象やページを絞ってください")
        requireLocalAccess(ConnectionCapability.READ)
        respondText(body, ContentType.Application.Json)
    } catch (failure: CancellationException) { throw failure }
    catch (failure: LocalAccessFailure) { throw failure }
    catch (failure: ReadFailure) { respondReadFailure(failure) }
    catch (_: IOException) { respondReadFailure(ReadFailure(ReadFailureCode.CHANGED_DURING_READ, "保存内容を読み取れませんでした。再試行してください")) }
    catch (_: Exception) {
        respondReadFailure(ReadFailure(ReadFailureCode.INVALID_DEFINITIONS, "読み取りを解決できません。デスクトップで定義と文書を確認してください"))
    }
}

internal suspend fun ApplicationCall.respondReadFailure(failure: ReadFailure) {
    val status = when (failure.code) {
        ReadFailureCode.INVALID_REQUEST -> 400
        ReadFailureCode.FORBIDDEN -> 403
        ReadFailureCode.NOT_FOUND -> 404
        ReadFailureCode.STALE_REVISION, ReadFailureCode.CHANGED_DURING_READ -> 409
        ReadFailureCode.LIMIT_EXCEEDED -> 413
        ReadFailureCode.INVALID_DEFINITIONS, ReadFailureCode.UNSUPPORTED_VIEW -> 422
        ReadFailureCode.BUSY -> 503
    }
    respondText(externalJson(mapOf("error" to mapOf("code" to failure.code.name.lowercase(), "message" to failure.message))).toString(),
        ContentType.Application.Json, HttpStatusCode.fromValue(status))
}
