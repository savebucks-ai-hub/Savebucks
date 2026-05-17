package com.savebucks.routes

import com.savebucks.lib.BadRequestException
import com.savebucks.lib.ConflictException
import com.savebucks.lib.supabase.SupabaseAdmin
import com.savebucks.middleware.requireAuth
import com.savebucks.models.successResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.koin.ktor.ext.inject

/**
 * Deal review routes.
 * Reviews include a 1-5 star rating plus optional text content.
 */
fun Route.reviewsRoutes() {
    val supabase: SupabaseAdmin by inject()

    route("/reviews") {

        /** GET /api/reviews/deals/:dealId — paginated reviews for a deal. */
        get("/deals/{dealId}") {
            val dealId = call.parameters["dealId"] ?: throw BadRequestException("Deal ID required")
            val sort = call.parameters["sort"] ?: "newest"
            val limit = (call.parameters["limit"]?.toIntOrNull() ?: 10).coerceIn(1, 50)
            val page = call.parameters["page"]?.toIntOrNull() ?: 1
            val offset = (page - 1) * limit

            var query = supabase.from("reviews")
                .select("*,profiles(handle,avatar_url)")
                .eq("deal_id", dealId)
                .eq("is_visible", true)
                .limit(limit)
                .offset(offset)

            query = when (sort) {
                "oldest" -> query.order("created_at", ascending = true)
                "highest_rated" -> query.order("rating", ascending = false)
                "lowest_rated" -> query.order("rating", ascending = true)
                "most_helpful" -> query.order("helpful_votes", ascending = false)
                else -> query.order("created_at", ascending = false)
            }

            val reviews = query.execute()

            // Aggregate rating stats
            val allRatings = supabase.from("reviews")
                .select("rating")
                .eq("deal_id", dealId)
                .eq("is_visible", true)
                .execute()

            val ratings = allRatings.mapNotNull { it.jsonObject["rating"]?.jsonPrimitive?.intOrNull }
            val avgRating = if (ratings.isEmpty()) 0.0 else ratings.average()
            val distribution = (1..5).associate { star ->
                star.toString() to ratings.count { it == star }
            }

            call.respond(HttpStatusCode.OK, successResponse(mapOf(
                "reviews" to reviews,
                "stats" to mapOf(
                    "average" to avgRating,
                    "total" to ratings.size,
                    "distribution" to distribution
                ),
                "page" to page,
                "hasMore" to (reviews.size >= limit)
            )))
        }

        authenticate("auth") {

            /** POST /api/reviews — submit a new review. */
            post {
                val user = call.requireAuth()
                val body = call.receive<CreateReviewRequest>()

                if (body.dealId.isBlank()) throw BadRequestException("deal_id is required")
                if (body.rating !in 1..5) throw BadRequestException("rating must be 1-5")
                if (body.content.length < 10) throw BadRequestException("Review content must be at least 10 characters")

                // Prevent duplicate reviews
                val existing = supabase.from("reviews")
                    .select("id")
                    .eq("deal_id", body.dealId)
                    .eq("user_id", user.userId)
                    .single()

                if (existing != null) throw ConflictException("You have already reviewed this deal")

                val review = supabase.insert("reviews",
                    buildJsonObject {
                        put("deal_id", body.dealId)
                        put("user_id", user.userId)
                        put("rating", body.rating)
                        put("content", body.content)
                        body.title?.let { put("title", it) }
                    }
                )
                call.respond(HttpStatusCode.Created, successResponse(review))
            }

            /** POST /api/reviews/:id/vote — vote on review helpfulness. */
            post("/{id}/vote") {
                val user = call.requireAuth()
                val reviewId = call.parameters["id"] ?: throw BadRequestException("Review ID required")
                val body = call.receive<ReviewVoteRequest>()

                supabase.upsert("review_votes",
                    buildJsonObject {
                        put("review_id", reviewId)
                        put("user_id", user.userId)
                        put("is_helpful", body.isHelpful)
                    },
                    onConflict = "review_id,user_id"
                )

                // Update helpful_votes counter
                val voteCount = supabase.from("review_votes")
                    .select("id")
                    .eq("review_id", reviewId)
                    .eq("is_helpful", true)
                    .count()

                supabase.update("reviews", buildJsonObject { put("helpful_votes", voteCount) })
                    .eq("id", reviewId).execute()

                call.respond(HttpStatusCode.OK, successResponse("Vote recorded"))
            }

            /** POST /api/reviews/:id/report — report a review for moderation. */
            post("/{id}/report") {
                val user = call.requireAuth()
                val reviewId = call.parameters["id"] ?: throw BadRequestException("Review ID required")
                val body = call.receive<ReportReviewRequest>()

                supabase.rpc("report_review",
                    buildJsonObject {
                        put("review_id", reviewId)
                        put("reporter_id", user.userId)
                        put("reason", body.reason)
                        body.description?.let { put("description", it) }
                    }
                )
                call.respond(HttpStatusCode.OK, successResponse("Review reported"))
            }
        }
    }
}

@Serializable private data class CreateReviewRequest(val dealId: String, val rating: Int, val content: String, val title: String? = null)
@Serializable private data class ReviewVoteRequest(val isHelpful: Boolean)
@Serializable private data class ReportReviewRequest(val reason: String, val description: String? = null)
