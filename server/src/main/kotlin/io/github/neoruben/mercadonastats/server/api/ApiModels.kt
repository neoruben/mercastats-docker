package io.github.neoruben.mercadonastats.server.api

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val error: String
)

@Serializable
data class HealthResponse(
    val status: String
)

@Serializable
data class UserDto(
    val id: Long,
    val username: String,
    val displayName: String,
    val email: String? = null,
    val isAdmin: Boolean,
    val authSource: String
)

@Serializable
data class AuthMeResponse(
    val authMode: String,
    val authenticated: Boolean,
    val user: UserDto? = null,
    val allowRegistration: Boolean,
    val imapConfigured: Boolean,
    val imapAllowed: Boolean,
    val gmailOAuthConfigured: Boolean,
    val gmailConnected: Boolean,
    val inboxEnabled: Boolean
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val displayName: String? = null,
    val email: String? = null
)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)

@Serializable
data class CreateUserRequest(
    val username: String,
    val password: String,
    val displayName: String? = null,
    val email: String? = null,
    val isAdmin: Boolean = false
)

@Serializable
data class TicketSummaryDto(
    val id: Long,
    val ticketNumber: String,
    val storeName: String,
    val storeAddress: String,
    val dateTimeIso: String,
    val dateDisplay: String,
    val totalAmount: Double,
    val totalVatAmount: Double,
    val itemCount: Int
)

@Serializable
data class TicketItemDto(
    val quantity: Double,
    val unit: String? = null,
    val description: String,
    val unitPrice: Double? = null,
    val totalPrice: Double
)

@Serializable
data class TicketVatDto(
    val vatRatePercent: Int,
    val baseAmount: Double,
    val vatAmount: Double,
    val totalAmount: Double
)

@Serializable
data class TicketDetailDto(
    val id: Long,
    val ticketNumber: String,
    val pdfSha256: String,
    val gmailMessageId: String? = null,
    val storeName: String,
    val storeAddress: String,
    val dateTimeIso: String,
    val dateDisplay: String,
    val totalAmount: Double,
    val totalVatAmount: Double,
    val items: List<TicketItemDto>,
    val vatBreakdown: List<TicketVatDto>
)

@Serializable
data class PeriodSpendStatDto(
    val periodKey: String,
    val periodLabel: String,
    val totalAmount: Double,
    val totalVatAmount: Double,
    val ticketCount: Int
)

@Serializable
data class ProductSpendStatDto(
    val productKey: String,
    val displayName: String,
    val unit: String? = null,
    val totalQuantity: Double,
    val purchaseCount: Int,
    val totalAmount: Double
)

@Serializable
data class StatsResponseDto(
    val totalSpent: Double,
    val totalVat: Double,
    val ticketCount: Int,
    val averageTicket: Double,
    val oldestDate: String? = null,
    val newestDate: String? = null,
    val monthlyStats: List<PeriodSpendStatDto>,
    val weeklyStats: List<PeriodSpendStatDto>
)

@Serializable
data class ProductsResponseDto(
    val total: Int,
    val sort: String,
    val products: List<ProductSpendStatDto>
)

@Serializable
data class ImportItemResultDto(
    val filename: String,
    val status: String,
    val ticketNumber: String? = null,
    val totalAmount: Double? = null,
    val error: String? = null
)

@Serializable
data class BatchImportResponseDto(
    val total: Int,
    val inserted: Int,
    val duplicates: Int,
    val failed: Int,
    val results: List<ImportItemResultDto>
)

@Serializable
data class SyncStateDto(
    val source: String,
    val lastRunMs: Long,
    val status: String,
    val message: String
)

@Serializable
data class SyncStatusResponseDto(
    val imapConfigured: Boolean,
    val imapAllowed: Boolean,
    val gmailOAuthConfigured: Boolean,
    val gmailConnected: Boolean,
    val inboxEnabled: Boolean,
    val states: List<SyncStateDto>,
    val imapProgress: ImapProgressDto? = null
)

@Serializable
data class PricePointDto(
    val ticketId: Long,
    val dateTimeIso: String,
    val unitPrice: Double,
    val quantity: Double
)

@Serializable
data class ProductPriceHistoryDto(
    val productKey: String,
    val displayName: String,
    val unit: String?,
    val points: List<PricePointDto>
)

@Serializable
data class ImapProgressDto(
    val phase: String,
    val done: Int,
    val total: Int,
    val inserted: Int,
    val duplicates: Int,
    val failed: Int
)

@Serializable
data class SyncStartedDto(
    val started: Boolean,
    val message: String
)

@Serializable
data class SyncRunResponseDto(
    val source: String,
    val totalMatched: Int,
    val processedNew: Int,
    val inserted: Int,
    val duplicates: Int,
    val failed: Int,
    val message: String
)

@Serializable
data class MessageResponseDto(
    val message: String
)
