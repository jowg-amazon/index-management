/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.indexmanagement.indexstatemanagement.validation

import org.apache.logging.log4j.LogManager
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.settings.Settings
import org.opensearch.indexmanagement.indexstatemanagement.opensearchapi.getRolloverAlias
import org.opensearch.indexmanagement.indexstatemanagement.opensearchapi.getRolloverSkip
import org.opensearch.indexmanagement.spi.indexstatemanagement.Step
import org.opensearch.indexmanagement.spi.indexstatemanagement.model.ActionMetaData
import org.opensearch.indexmanagement.spi.indexstatemanagement.model.ManagedIndexMetaData
import org.opensearch.indexmanagement.spi.indexstatemanagement.model.StepContext
import org.opensearch.indexmanagement.util.OpenForTesting

@OpenForTesting
class ValidateRollover(
    settings: Settings,
    clusterService: ClusterService
) : Validate(settings, clusterService) {

    private val logger = LogManager.getLogger(javaClass)
    private var validationInfo: Map<String, Any>? = null

    // returns a Validate object with updated validation and step status
    @Suppress("ReturnSuppressCount", "ReturnCount")
    override fun executeValidation(context: StepContext): Validate {
        val (rolloverTarget, isDataStream) = getRolloverTargetOrUpdateInfo(context)
        rolloverTarget ?: return this

        if (skipRollover(context, clusterService) || alreadyRolledOver(context, clusterService, rolloverTarget)) return this

        if (!isDataStream) {
            if (!hasAlias(context, rolloverTarget) || !isWriteIndex(context, rolloverTarget)
            ) {
                return this
            }
        }

        return this
    }

    // validation logic------------------------------------------------------------------------------------------------

    private fun skipRollover(context: StepContext, clusterService: ClusterService): Boolean {
        val indexName = context.metadata.index
        val skipRollover = clusterService.state().metadata.index(indexName).getRolloverSkip()
        if (skipRollover) {
            stepStatus = Step.StepStatus.COMPLETED
            validationStatus = ValidationStatus.PASS
            validationInfo = mapOf("message" to getSkipRolloverMessage(indexName))
            return true
        }
        return false
    }

    private fun alreadyRolledOver(context: StepContext, clusterService: ClusterService, alias: String?): Boolean {
        val indexName = context.metadata.index
        if (clusterService.state().metadata.index(indexName).rolloverInfos.containsKey(alias)) {
            stepStatus = Step.StepStatus.COMPLETED
            validationStatus = ValidationStatus.PASS
            validationInfo = mapOf("message" to getAlreadyRolledOverMessage(indexName, alias))
            return true
        }
        return false
    }

    private fun hasAlias(context: StepContext, alias: String?): Boolean {
        val indexName = context.metadata.index
        val metadata = context.clusterService.state().metadata
        val indexAlias = metadata.index(indexName)?.aliases?.get(alias)

        logger.debug("Index $indexName has aliases $indexAlias")
        if (indexAlias == null) {
            val message = getMissingAliasMessage(indexName)
            logger.warn(message)
            stepStatus = Step.StepStatus.VALIDATION_FAILED
            validationStatus = ValidationStatus.REVALIDATE
            validationInfo = mapOf("message" to message)
            return false
        }
        return true
    }

    private fun isWriteIndex(context: StepContext, alias: String?): Boolean {
        val indexName = context.metadata.index
        val metadata = context.clusterService.state().metadata
        val indexAlias = metadata.index(indexName)?.aliases?.get(alias)

        val isWriteIndex = indexAlias?.writeIndex() // this could be null
        if (isWriteIndex != true) {
            val aliasIndices = metadata.indicesLookup[alias]?.indices?.map { it.index }
            logger.debug("Alias $alias contains indices $aliasIndices")
            if (aliasIndices != null && aliasIndices.size > 1) {
                val message = getFailedWriteIndexMessage(indexName)
                logger.warn(message)
                stepStatus = Step.StepStatus.VALIDATION_FAILED
                validationStatus = ValidationStatus.REVALIDATE
                validationInfo = mapOf("message" to message)
                return false
            }
        }
        return true
    }

    private fun getRolloverTargetOrUpdateInfo(context: StepContext): Pair<String?, Boolean> {
        val indexName = context.metadata.index
        val metadata = context.clusterService.state().metadata()
        val indexAbstraction = metadata.indicesLookup[indexName]
        val isDataStreamIndex = indexAbstraction?.parentDataStream != null

        val rolloverTarget = when {
            isDataStreamIndex -> indexAbstraction?.parentDataStream?.name
            else -> metadata.index(indexName).getRolloverAlias()
        }

        if (rolloverTarget == null) {
            val message = getFailedNoValidAliasMessage(indexName)
            logger.warn(message)
            stepStatus = Step.StepStatus.VALIDATION_FAILED
            validationStatus = ValidationStatus.REVALIDATE
            validationInfo = mapOf("message" to message)
        }

        return rolloverTarget to isDataStreamIndex
    }

    override fun getUpdatedManagedIndexMetadata(currentMetadata: ManagedIndexMetaData, actionMetaData: ActionMetaData): ManagedIndexMetaData {
        return currentMetadata.copy(
            actionMetaData = actionMetaData,
            validationInfo = validationInfo,
            // add a validation error field
        )
    }

    // TODO: 7/18/22
    override fun validatePolicy(): Boolean {
//        val states = request.policy.states
//        for (state in states) {
//            for (action in state.actions) {
//                if (action is ReplicaCountAction) {
//                    val updatedNumberOfReplicas = action.numOfReplicas
//                    val error = awarenessReplicaBalance.validate(updatedNumberOfReplicas)
//                    if (error.isPresent) {
//                        val ex = ValidationException()
//                        ex.addValidationError(error.get())
//                        actionListener.onFailure(ex)
//                    }
//                }
//            }
//        }
        return true
    }

    @Suppress("TooManyFunctions")
    companion object {
        const val name = "attempt_rollover"
        fun getFailedMessage(index: String) = "Failed to rollover index [index=$index]"
        fun getFailedWriteIndexMessage(index: String) = "Not the write index when rollover [index=$index]"
        fun getMissingAliasMessage(index: String) = "Missing alias when rollover [index=$index]"
        fun getFailedNoValidAliasMessage(index: String) = "Missing rollover_alias index setting [index=$index]"
        fun getAlreadyRolledOverMessage(index: String, alias: String?) =
            "This index has already been rolled over using this alias, treating as a success [index=$index, alias=$alias]"
        fun getSkipRolloverMessage(index: String) = "Skipped rollover action for [index=$index]"
    }
}