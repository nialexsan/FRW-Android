package com.flowfoundation.wallet.page.staking

import android.content.Context
import com.flowfoundation.wallet.manager.staking.StakingManager
import com.flowfoundation.wallet.page.staking.detail.StakingDetailActivity
import com.flowfoundation.wallet.page.staking.guide.StakeGuideActivity
import com.flowfoundation.wallet.page.staking.list.StakingListActivity
import com.flowfoundation.wallet.page.staking.providers.StakingProviderActivity


fun openStakingPage(context: Context) {
    if (StakingManager.isStaked()) {
        if (StakingManager.stakingInfo().nodes.isEmpty()) {
            StakingProviderActivity.launch(context)
        } else if (StakingManager.stakingInfo().nodes.size == 1) {
            val provider = StakingManager.providers().firstOrNull { it.id == StakingManager.stakingInfo().nodes.first().nodeID } ?: return
            StakingDetailActivity.launch(context, provider)
        } else StakingListActivity.launch(context)
    } else StakeGuideActivity.launch(context)
}
