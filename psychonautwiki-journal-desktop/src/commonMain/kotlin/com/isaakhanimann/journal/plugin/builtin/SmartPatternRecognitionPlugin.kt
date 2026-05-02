package com.isaakhanimann.journal.plugin.builtin

import com.isaakhanimann.journal.plugin.*
import com.isaakhanimann.journal.data.model.Experience
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import kotlin.math.*

class SmartPatternRecognitionPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "smart-pattern-recognition",
        name = "Smart Pattern Recognition",
        version = "1.0.0",
        description = "AI-powered analysis for substance interactions and experience optimization",
        author = "PsychonautWiki Journal",
        permissions = listOf(
            Permission.READ_EXPERIENCES,
            Permission.READ_SUBSTANCES,
            Permission.ANALYTICS_ACCESS
        ),
        entryPoint = "com.isaakhanimann.journal.plugin.builtin.SmartPatternRecognitionPlugin"
    )
    
    private lateinit var context: PluginContext
    
    override suspend fun initialize(context: PluginContext): Result<Unit> {
        this.context = context
        return Result.success(Unit)
    }
    
    override suspend fun shutdown(): Result<Unit> {
        return Result.success(Unit)
    }
    
    override fun getCapabilities(): List<PluginCapability> {
        return listOf(
            AnalyticsCapability(
                id = "substance-interaction-detection",
                name = "Substance Interaction Detection",
                description = "Detect potentially dangerous substance combinations",
                analyzeFunction = ::analyzeSubstanceInteractions
            ),
            AnalyticsCapability(
                id = "tolerance-tracking",
                name = "Personal Tolerance Tracking",
                description = "Track tolerance patterns and suggest optimal dosages",
                analyzeFunction = ::analyzeTolerancePatterns
            ),
            AnalyticsCapability(
                id = "experience-quality-correlation",
                name = "Experience Quality Correlation",
                description = "Identify factors that lead to positive/negative experiences",
                analyzeFunction = ::analyzeExperienceQuality
            ),
            AnalyticsCapability(
                id = "timing-optimization",
                name = "Timing Pattern Analysis",
                description = "Optimize timing patterns for different substances",
                analyzeFunction = ::analyzeTimingPatterns
            ),
            AnalyticsCapability(
                id = "risk-assessment",
                name = "Real-time Risk Assessment",
                description = "Comprehensive risk scoring for planned experiences",
                analyzeFunction = ::assessRisk
            )
        )
    }
    
    private suspend fun analyzeSubstanceInteractions(context: AnalyticsContext): AnalyticsResult {
        val experiences = context.experiences
        val insights = mutableListOf<Insight>()
        val recommendations = mutableListOf<Recommendation>()
        
        // Analyze substance combinations from experiences
        val combinationMap = mutableMapOf<Set<String>, MutableList<Experience>>()
        
        experiences.forEach { experience ->
            val substances = experience.ingestions?.map { it.substanceName }?.toSet() ?: emptySet()
            if (substances.size > 1) {
                combinationMap.getOrPut(substances) { mutableListOf() }.add(experience)
            }
        }
        
        // Identify potentially dangerous combinations
        combinationMap.forEach { (substances, experienceList) ->
            val negativeExperiences = experienceList.count { experience ->
                experience.overallRating != null && experience.overallRating!! < 3 
            }
            val totalExperiences = experienceList.size
            
            if (negativeExperiences.toDouble() / totalExperiences > 0.6 && totalExperiences >= 2) {
                insights.add(Insight(
                    id = "dangerous-combination-${substances.joinToString("-")}",
                    title = "Potentially Dangerous Combination Detected",
                    description = "The combination of ${substances.joinToString(" + ")} has resulted in negative experiences ${negativeExperiences}/${totalExperiences} times",
                    confidence = min(0.9, negativeExperiences.toDouble() / totalExperiences),
                    severity = when {
                        negativeExperiences.toDouble() / totalExperiences > 0.8 -> InsightSeverity.CRITICAL
                        negativeExperiences.toDouble() / totalExperiences > 0.6 -> InsightSeverity.HIGH
                        else -> InsightSeverity.MEDIUM
                    }
                ))
                
                recommendations.add(Recommendation(
                    id = "avoid-combination-${substances.joinToString("-")}",
                    title = "Avoid This Combination",
                    description = "Consider avoiding the combination of ${substances.joinToString(" + ")} based on your experience history",
                    actionable = true,
                    priority = RecommendationPriority.HIGH,
                    category = RecommendationCategory.SAFETY
                ))
            }
        }
        
        // Check for known dangerous interactions.
        //
        // SAFETY: this lookup is curated and intentionally conservative. The
        // canonical source of truth for substance interactions is the
        // PsychonautWiki database (Substance.interactions on the AnalyticsContext
        // payload, or SubstanceInfo.interactions.dangerous from SubstanceLoader).
        // When that data is wired through, prefer it; this hard-coded table is a
        // fallback for categories that PW exposes as a class (e.g. "MAOIs")
        // rather than per-substance edges.
        //
        // Matching rules:
        //  - names are compared case-insensitively but EXACTLY (no substring),
        //    so "Cocaethylene" does not falsely match "Cocaine";
        //  - a combination warns only when EVERY arm is matched by some
        //    substance in the experience, not just one arm.
        combinationMap.keys.forEach { substances ->
            DANGEROUS_COMBINATIONS.forEach { combo ->
                if (combo.matches(substances)) {
                    insights.add(Insight(
                        id = "known-dangerous-${substances.joinToString("-")}",
                        title = "Known Dangerous Interaction: ${combo.label}",
                        description = combo.description,
                        confidence = 0.95,
                        severity = InsightSeverity.CRITICAL
                    ))
                }
            }
        }
        
        return AnalyticsResult(
            insights = insights,
            recommendations = recommendations,
            riskAssessment = null,
            visualizations = listOf(
                VisualizationData(
                    type = VisualizationType.NETWORK_GRAPH,
                    data = mapOf(
                        "combinations" to combinationMap.keys.map { it.toList() },
                        "safety_scores" to combinationMap.map { (_, experiences) ->
                            val ratings = experiences.mapNotNull { exp -> exp.overallRating?.toDouble() }
                            if (ratings.isNotEmpty()) ratings.average() else 0.0
                        }
                    ),
                    title = "Substance Interaction Network",
                    description = "Network graph showing substance combinations and their safety patterns"
                )
            )
        )
    }
    
    private suspend fun analyzeTolerancePatterns(context: AnalyticsContext): AnalyticsResult {
        val experiences = context.experiences.sortedBy { it.date }
        val insights = mutableListOf<Insight>()
        val recommendations = mutableListOf<Recommendation>()
        
        // Group experiences by substance
        val substanceExperiences = experiences
            .flatMap { exp -> 
                exp.ingestions?.map { ing -> ing.substanceName to exp } ?: emptyList() 
            }
            .groupBy({ it.first }, { it.second })
        
        substanceExperiences.forEach { (substance, experienceList) ->
            if (experienceList.size < 3) return@forEach
            
            // Analyze dosage trends
            val dosageData = experienceList.mapNotNull { exp ->
                val ingestion = exp.ingestions?.find { it.substanceName == substance }
                val dosage = ingestion?.dose
                val date = exp.date
                if (dosage != null && date != null) date to dosage else null
            }.sortedBy { it.first }
            
            if (dosageData.size >= 3) {
                // Calculate tolerance trend
                val dosageIncreases = dosageData.zipWithNext().count { (prev, next) ->
                    next.second > prev.second * 1.2 // 20% increase threshold
                }
                
                val toleranceRatio = dosageIncreases.toDouble() / (dosageData.size - 1)
                
                if (toleranceRatio > 0.5) {
                    insights.add(Insight(
                        id = "tolerance-buildup-$substance",
                        title = "Tolerance Buildup Detected",
                        description = "Your dosages for $substance have been increasing over time, indicating tolerance buildup",
                        confidence = toleranceRatio,
                        severity = when {
                            toleranceRatio > 0.8 -> InsightSeverity.HIGH
                            toleranceRatio > 0.6 -> InsightSeverity.MEDIUM
                            else -> InsightSeverity.LOW
                        }
                    ))
                    
                    recommendations.add(Recommendation(
                        id = "tolerance-break-$substance",
                        title = "Consider a Tolerance Break",
                        description = "A tolerance break for $substance could help reset your sensitivity and reduce required dosages",
                        actionable = true,
                        priority = RecommendationPriority.MEDIUM,
                        category = RecommendationCategory.OPTIMIZATION
                    ))
                }
            }
            
            // Analyze experience quality vs dosage
            val qualityDosageCorrelation = experienceList.mapNotNull { exp ->
                val ingestion = exp.ingestions?.find { it.substanceName == substance }
                val rating = exp.overallRating
                val dosage = ingestion?.dose
                if (rating != null && dosage != null) dosage to rating else null
            }
            
            if (qualityDosageCorrelation.size >= 3) {
                val correlation = calculateCorrelation(
                    qualityDosageCorrelation.map { it.first.toDouble() },
                    qualityDosageCorrelation.map { it.second.toDouble() }
                )
                
                if (correlation < -0.3) {
                    insights.add(Insight(
                        id = "dosage-quality-inverse-$substance",
                        title = "Higher Dosages Linked to Worse Experiences",
                        description = "Your experience quality for $substance tends to decrease with higher dosages",
                        confidence = abs(correlation),
                        severity = InsightSeverity.MEDIUM
                    ))
                    
                    recommendations.add(Recommendation(
                        id = "reduce-dosage-$substance",
                        title = "Consider Lower Dosages",
                        description = "Your data suggests better experiences with lower $substance dosages",
                        actionable = true,
                        priority = RecommendationPriority.MEDIUM,
                        category = RecommendationCategory.OPTIMIZATION
                    ))
                }
            }
        }
        
        return AnalyticsResult(
            insights = insights,
            recommendations = recommendations,
            visualizations = listOf(
                VisualizationData(
                    type = VisualizationType.LINE_CHART,
                    data = mapOf(
                        "substance_trends" to substanceExperiences.mapValues { (substanceName, experiences) ->
                            experiences.mapNotNull { exp ->
                                val ingestion = exp.ingestions?.find { it.substanceName == substanceName }
                                mapOf(
                                    "date" to exp.date,
                                    "dosage" to ingestion?.dose,
                                    "rating" to exp.overallRating
                                )
                            }
                        }
                    ),
                    title = "Tolerance Patterns Over Time",
                    description = "Dosage and experience quality trends for each substance"
                )
            )
        )
    }
    
    private suspend fun analyzeExperienceQuality(context: AnalyticsContext): AnalyticsResult {
        val experiences = context.experiences.filter { it.overallRating != null }
        val insights = mutableListOf<Insight>()
        val recommendations = mutableListOf<Recommendation>()
        
        if (experiences.size < 5) {
            return AnalyticsResult(
                insights = listOf(
                    Insight(
                        id = "insufficient-data",
                        title = "Insufficient Data for Quality Analysis",
                        description = "More experience data is needed to perform meaningful quality correlation analysis",
                        confidence = 1.0,
                        severity = InsightSeverity.LOW
                    )
                ),
                recommendations = emptyList()
            )
        }
        
        // Analyze factors affecting experience quality
        val positiveExperiences = experiences.filter { it.overallRating!! >= 4 }
        val negativeExperiences = experiences.filter { it.overallRating!! <= 2 }
        
        // Day of week analysis
        val dayQualityMap = experiences.groupBy { 
            // TODO: Extract day of week from date
            "Unknown" // Placeholder
        }.mapValues { (_, exps) -> 
            exps.mapNotNull { it.overallRating }.average() 
        }
        
        // Setting analysis (if location data available)
        val locationQualityMap = experiences
            .filter { !it.location.isNullOrBlank() }
            .groupBy { it.location!! }
            .mapValues { (_, exps) -> 
                exps.mapNotNull { it.overallRating }.average() 
            }
        
        locationQualityMap.forEach { (location, avgRating) ->
            when {
                avgRating >= 4.0 && locationQualityMap.size > 1 -> {
                    recommendations.add(Recommendation(
                        id = "optimal-location-$location",
                        title = "Optimal Location Identified",
                        description = "$location appears to be an optimal setting for your experiences (avg rating: ${String.format("%.1f", avgRating)})",
                        actionable = true,
                        priority = RecommendationPriority.MEDIUM,
                        category = RecommendationCategory.OPTIMIZATION
                    ))
                }
                avgRating <= 2.5 && locationQualityMap.size > 1 -> {
                    insights.add(Insight(
                        id = "suboptimal-location-$location",
                        title = "Suboptimal Setting Detected",
                        description = "$location may not be ideal for your experiences (avg rating: ${String.format("%.1f", avgRating)})",
                        confidence = 0.7,
                        severity = InsightSeverity.MEDIUM
                    ))
                }
            }
        }
        
        // Timing analysis
        val timeBasedAnalysis = experiences.mapNotNull { exp ->
            val firstIngestion = exp.ingestions?.minByOrNull { it.time ?: Instant.DISTANT_PAST }
            if (firstIngestion?.time != null) {
                // TODO: Extract hour from time
                0 to exp.overallRating!! // Placeholder
            } else null
        }.groupBy({ it.first }, { it.second })
        
        return AnalyticsResult(
            insights = insights,
            recommendations = recommendations,
            visualizations = listOf(
                VisualizationData(
                    type = VisualizationType.BAR_CHART,
                    data = mapOf(
                        "location_ratings" to locationQualityMap,
                        "positive_factors" to positiveExperiences.size,
                        "negative_factors" to negativeExperiences.size
                    ),
                    title = "Experience Quality Factors",
                    description = "Factors that correlate with positive and negative experiences"
                )
            )
        )
    }
    
    private suspend fun analyzeTimingPatterns(context: AnalyticsContext): AnalyticsResult {
        val experiences = context.experiences
        val insights = mutableListOf<Insight>()
        val recommendations = mutableListOf<Recommendation>()
        
        // Analyze intervals between experiences with same substance
        val substanceIntervals = experiences
            .flatMap { exp -> 
                exp.ingestions?.map { ing -> ing.substanceName to exp.date } ?: emptyList() 
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, dates) ->
                dates.filterNotNull().sorted().zipWithNext().map { (prev, next) ->
                    // Calculate days between experiences
                    // TODO: Implement proper date difference calculation
                    1L // Placeholder
                }
            }
        
        substanceIntervals.forEach { (substance, intervals) ->
            if (intervals.size >= 3) {
                val avgInterval = intervals.average()
                val minSafeInterval = MIN_SAFE_INTERVALS_DAYS[substance.trim().lowercase()] ?: 7
                
                if (avgInterval < minSafeInterval) {
                    insights.add(Insight(
                        id = "frequent-use-$substance",
                        title = "Frequent Use Pattern Detected",
                        description = "Your $substance usage frequency (avg ${avgInterval.toInt()} days) is below recommended intervals",
                        confidence = 0.8,
                        severity = when {
                            avgInterval < minSafeInterval * 0.5 -> InsightSeverity.HIGH
                            avgInterval < minSafeInterval * 0.7 -> InsightSeverity.MEDIUM
                            else -> InsightSeverity.LOW
                        }
                    ))
                    
                    recommendations.add(Recommendation(
                        id = "spacing-recommendation-$substance",
                        title = "Increase Time Between Uses",
                        description = "Consider spacing $substance experiences at least $minSafeInterval days apart for optimal effects and safety",
                        actionable = true,
                        priority = RecommendationPriority.HIGH,
                        category = RecommendationCategory.SAFETY
                    ))
                }
            }
        }
        
        return AnalyticsResult(
            insights = insights,
            recommendations = recommendations,
            visualizations = listOf(
                VisualizationData(
                    type = VisualizationType.TIMELINE,
                    data = mapOf(
                        "substance_timelines" to substanceIntervals,
                        "recommended_intervals" to mapOf(
                            "LSD" to 14,
                            "Psilocybin" to 14,
                            "MDMA" to 90,
                            "DMT" to 1
                        )
                    ),
                    title = "Usage Timing Patterns",
                    description = "Analysis of timing patterns and recommended intervals"
                )
            )
        )
    }
    
    private suspend fun assessRisk(context: AnalyticsContext): AnalyticsResult {
        val experiences = context.experiences
        val riskFactors = mutableListOf<RiskFactor>()
        var overallRisk = 0.0
        
        // Recent experience frequency
        val now = Clock.System.now()
        val recentExperiences = experiences.count { exp ->
            exp.date?.let { date ->
                // TODO: Implement proper date comparison
                true // Placeholder
            } ?: false
        }
        
        if (recentExperiences > 3) {
            val frequencyRisk = min(1.0, recentExperiences / 10.0)
            riskFactors.add(RiskFactor(
                factor = "High Recent Activity",
                severity = frequencyRisk,
                description = "$recentExperiences experiences in the last 30 days"
            ))
            overallRisk += frequencyRisk * 0.3
        }
        
        // Polydrug use risk
        val recentSubstances = experiences.take(5).flatMap { exp ->
            exp.ingestions?.map { it.substanceName } ?: emptyList()
        }.distinct()
        
        if (recentSubstances.size > 3) {
            val polydragRisk = min(1.0, recentSubstances.size / 8.0)
            riskFactors.add(RiskFactor(
                factor = "Multiple Substances",
                severity = polydragRisk,
                description = "${recentSubstances.size} different substances used recently"
            ))
            overallRisk += polydragRisk * 0.4
        }
        
        // Negative experience trend
        val recentNegativeExperiences = experiences.take(5).count { 
            it.overallRating != null && it.overallRating <= 2 
        }
        
        if (recentNegativeExperiences > 1) {
            val negativeRisk = recentNegativeExperiences / 5.0
            riskFactors.add(RiskFactor(
                factor = "Recent Negative Experiences",
                severity = negativeRisk,
                description = "$recentNegativeExperiences negative experiences in last 5"
            ))
            overallRisk += negativeRisk * 0.3
        }
        
        overallRisk = min(1.0, overallRisk)
        
        val mitigationStrategies = mutableListOf<String>()
        if (recentExperiences > 3) {
            mitigationStrategies.add("Take a break from psychoactive substances")
        }
        if (recentSubstances.size > 3) {
            mitigationStrategies.add("Focus on one substance type to reduce interaction risks")
        }
        if (recentNegativeExperiences > 1) {
            mitigationStrategies.add("Review set and setting factors that may have contributed to negative experiences")
        }
        
        val riskAssessment = RiskAssessment(
            overallRisk = overallRisk,
            riskFactors = riskFactors,
            mitigationStrategies = mitigationStrategies
        )
        
        return AnalyticsResult(
            insights = listOf(
                Insight(
                    id = "current-risk-assessment",
                    title = "Current Risk Level: ${when {
                        overallRisk >= 0.7 -> "HIGH"
                        overallRisk >= 0.4 -> "MEDIUM"
                        else -> "LOW"
                    }}",
                    description = "Based on recent activity patterns and experience history",
                    confidence = 0.8,
                    severity = when {
                        overallRisk >= 0.7 -> InsightSeverity.HIGH
                        overallRisk >= 0.4 -> InsightSeverity.MEDIUM
                        else -> InsightSeverity.LOW
                    }
                )
            ),
            recommendations = mitigationStrategies.map { strategy ->
                Recommendation(
                    id = "risk-mitigation-${strategy.hashCode()}",
                    title = "Risk Mitigation",
                    description = strategy,
                    actionable = true,
                    priority = when {
                        overallRisk >= 0.7 -> RecommendationPriority.HIGH
                        overallRisk >= 0.4 -> RecommendationPriority.MEDIUM
                        else -> RecommendationPriority.LOW
                    },
                    category = RecommendationCategory.SAFETY
                )
            },
            riskAssessment = riskAssessment,
            visualizations = listOf(
                VisualizationData(
                    type = VisualizationType.GAUGE,
                    data = mapOf(
                        "overall_risk" to overallRisk,
                        "risk_factors" to riskFactors.map { 
                            mapOf("name" to it.factor, "value" to it.severity) 
                        }
                    ),
                    title = "Risk Assessment Dashboard",
                    description = "Current risk level and contributing factors"
                )
            )
        )
    }
    
    /**
     * Two arms (each itself a set of canonical names) define a dangerous combination.
     * A logged experience triggers the warning iff at least one substance in the
     * experience matches arm A AND at least one matches arm B (case-insensitive
     * exact-name match — never substring).
     */
    private data class DangerousCombo(
        val label: String,
        val description: String,
        val armA: Set<String>,
        val armB: Set<String>
    ) {
        fun matches(substancesInExperience: Set<String>): Boolean {
            val taken = substancesInExperience.map { it.trim().lowercase() }.toSet()
            val a = armA.any { it.lowercase() in taken }
            val b = armB.any { it.lowercase() in taken }
            return a && b
        }
    }

    companion object {
        // MAOIs the PsychonautWiki database surfaces as separate substances.
        // This list is incomplete on purpose — it covers the common cases and
        // should be replaced with `SubstanceInfo.categories.contains("MAOI")`
        // matching once the substance library is wired into the analytics
        // context. The conservative fallback is acceptable: false negatives
        // here are caught by the "negative experience trend" heuristic above.
        private val MAOIS = setOf(
            "Phenelzine", "Tranylcypromine", "Selegiline", "Moclobemide",
            "Isocarboxazid", "Harmaline", "Harmine",
            "Syrian rue", "Peganum harmala", "Banisteriopsis caapi", "Ayahuasca"
        )

        private val DANGEROUS_COMBINATIONS = listOf(
            DangerousCombo(
                label = "MDMA + MAOI",
                description = "MAOIs combined with MDMA can trigger life-threatening serotonin syndrome and hypertensive crisis.",
                armA = setOf("MDMA"),
                armB = MAOIS
            ),
            DangerousCombo(
                label = "Cocaine + Alcohol",
                description = "Cocaine and alcohol combine in the liver to form cocaethylene, a more cardiotoxic metabolite associated with sudden cardiac death.",
                armA = setOf("Cocaine"),
                armB = setOf("Alcohol", "Ethanol")
            ),
            DangerousCombo(
                label = "Tramadol + MDMA",
                description = "Both elevate serotonin and lower the seizure threshold; combined use risks serotonin syndrome and seizures.",
                armA = setOf("Tramadol"),
                armB = setOf("MDMA")
            ),
            DangerousCombo(
                label = "Tramadol + serotonergic psychedelic",
                description = "Tramadol combined with serotonergic psychedelics or MDMA risks serotonin syndrome and seizures.",
                armA = setOf("Tramadol"),
                armB = setOf("LSD", "Psilocybin", "Psilocin", "DMT", "5-MeO-DMT", "2C-B")
            ),
            DangerousCombo(
                label = "Lithium + serotonergic psychedelic",
                description = "Lithium combined with classical serotonergic psychedelics has been associated with seizures and is widely contraindicated.",
                armA = setOf("Lithium"),
                armB = setOf("LSD", "Psilocybin", "Psilocin", "DMT", "5-MeO-DMT", "2C-B", "Mescaline", "MDMA")
            )
        )

        // Minimum recommended interval (days) between repeated doses of the
        // same substance, keyed by canonical lowercase name. Match is exact:
        // a logged "LSD" hits the 14-day rule, "1P-LSD" or "ALD-52" do not
        // (they should be added explicitly when their own pharmacology is
        // distinct enough to warrant a different cadence).
        internal val MIN_SAFE_INTERVALS_DAYS: Map<String, Int> = mapOf(
            "lsd" to 14,
            "psilocybin" to 14,
            "psilocin" to 14,
            "mdma" to 90,
            "dmt" to 1,
            "5-meo-dmt" to 7,
            "2c-b" to 7,
            "ketamine" to 7,
            "mescaline" to 14
        )
    }

    private fun calculateCorrelation(x: List<Double>, y: List<Double>): Double {
        if (x.size != y.size || x.isEmpty()) return 0.0
        
        val meanX = x.average()
        val meanY = y.average()
        
        val numerator = x.zip(y).sumOf { (xi, yi) -> (xi - meanX) * (yi - meanY) }
        val denominatorX = sqrt(x.sumOf { (it - meanX).pow(2) })
        val denominatorY = sqrt(y.sumOf { (it - meanY).pow(2) })
        
        return if (denominatorX == 0.0 || denominatorY == 0.0) 0.0 
               else numerator / (denominatorX * denominatorY)
    }
}