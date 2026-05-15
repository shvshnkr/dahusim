package fr.husi.bg

import fr.husi.ktx.Logs
import fr.husi.ktx.runOnDefaultDispatcher

internal interface DesktopTaskDefinition {
    val id: String
    suspend fun run()
}

internal object DesktopTaskRegistry {
    private val subscriptionAutoUpdateTask = object : DesktopTaskDefinition {
        override val id: String = "subscription-auto-update"

        override suspend fun run() {
            SubscriptionAutoUpdateRunner.run(mode = SubscriptionUpdateMode.BackgroundEco)
        }
    }

    private val routeAssetAutoUpdateTask = object : DesktopTaskDefinition {
        override val id: String = "route-asset-auto-update"

        override suspend fun run() {
            RouteAssetAutoUpdateRunner.run()
        }
    }

    private val definitions = listOf(subscriptionAutoUpdateTask, routeAssetAutoUpdateTask)
        .associateBy(DesktopTaskDefinition::id)

    fun require(taskId: String): DesktopTaskDefinition {
        return definitions[taskId] ?: error("Unknown desktop task: $taskId")
    }

    fun dispatch(taskId: String) {
        runOnDefaultDispatcher {
            runCatching {
                require(taskId).run()
            }.onFailure {
                Logs.e("run desktop task $taskId", it)
            }
        }
    }
}
