package io.nekohasekai.sagernet.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.moveUserOrder
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.scrollTo
import io.nekohasekai.sagernet.widget.UndoSnackbarManager

class ConfigurationAdapter(private val groupFragment: ProfileListFragment) :
    RecyclerView.Adapter<ConfigurationHolder>(),
    ProfileManager.Listener,
    GroupManager.Listener,
    UndoSnackbarManager.Interface<ProxyEntity> {

    init {
        setHasStableIds(true)
    }

    var configurationIdList: MutableList<Long> = mutableListOf()
    val configurationList = HashMap<Long, ProxyEntity>()

    private fun getItem(profileId: Long): ProxyEntity? {
        var profile = configurationList[profileId]
        if (profile == null) {
            profile = ProfileManager.getProfile(profileId)
            if (profile != null) {
                configurationList[profileId] = profile
            }
        }
        return profile
    }

    private fun getItemAt(index: Int) = getItem(configurationIdList[index])

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ConfigurationHolder {
        return ConfigurationHolder(
            groupFragment,
            LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_profile, parent, false)
        )
    }

    override fun getItemId(position: Int): Long {
        return configurationIdList[position]
    }

    override fun onBindViewHolder(holder: ConfigurationHolder, position: Int) {
        try {
            getItemAt(position)?.let { holder.bind(it) }
        } catch (ignored: NullPointerException) { // when group deleted
        }
    }

    override fun getItemCount(): Int {
        return configurationIdList.size
    }

    private val updated = HashSet<ProxyEntity>()

    // every id in group order, unaffected by filter(): the HashMap's key
    // order used to scramble search results
    private val allProfileIds = mutableListOf<Long>()

    // Derived, not tracked: the visible list is always a subsequence of
    // allProfileIds, so a shorter one means a filter is hiding rows and its
    // positions no longer map to the search source. Dragging is disabled while
    // that holds (getDragDirs), which keeps move() in a single index space.
    val isFiltered get() = configurationIdList.size != allProfileIds.size

    fun filter(name: String) {
        if (name.isEmpty()) {
            reloadProfiles()
            return
        }
        configurationIdList.clear()
        val lower = name.lowercase()
        configurationIdList.addAll(allProfileIds.filter { id ->
            val profile = configurationList[id] ?: return@filter false
            profile.displayName().lowercase().contains(lower) ||
                    profile.displayType().lowercase().contains(lower) ||
                    profile.displayAddress().lowercase().contains(lower)
        })
        notifyDataSetChanged()
    }

    // dragging is disabled while filtering, so the visible list and the search
    // source share indices here and both can be permuted in one pass
    fun move(from: Int, to: Int) {
        val moved = moveUserOrder(from, to, ProxyEntity::userOrder, ::getItemAt) { i, profile ->
            configurationIdList[i] = profile.id
            allProfileIds[i] = profile.id
            updated.add(profile)
        }
        if (moved) notifyItemMoved(from, to)
    }

    fun commitMove() {
        // swap out the pending moves on the main thread: move() adds
        // to `updated` there while the write below iterates it
        val updated = HashSet(updated)
        this.updated.clear()
        runOnDefaultDispatcher {
            ProfileRepository.updateUserOrders(updated)
        }
    }

    fun remove(pos: Int) {
        if (pos < 0) return
        // drop it from the search source as well: until the undo snackbar
        // commits the deletion, filter() would still surface the removed profile
        allProfileIds.remove(configurationIdList[pos])
        configurationIdList.removeAt(pos)
        notifyItemRemoved(pos)
    }

    override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
        for ((index, item) in actions) {
            groupFragment.configurationListView.post {
                configurationList[item.id] = item
                configurationIdList.add(index, item.id)
                // back into the search source, anchored on the row it was
                // restored above: deleting is allowed while filtering, and
                // `index` is then a visible position the full list has never
                // shared
                val anchor = configurationIdList.getOrNull(index + 1)
                allProfileIds.add(
                    anchor?.let { allProfileIds.indexOf(it) }?.takeIf { it >= 0 }
                        ?: allProfileIds.size, item.id
                )
                notifyItemInserted(index)
            }
        }
    }

    override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
        val profiles = actions.map { it.second }
        runOnDefaultDispatcher {
            for (entity in profiles) {
                ProfileManager.deleteProfile(entity.groupId, entity.id)
            }
        }
    }

    override suspend fun onAdd(profile: ProxyEntity) {
        if (profile.groupId != groupFragment.proxyGroup.id) return

        groupFragment.configurationListView.post {
            if (groupFragment.isUndoManagerInitialized) {
                groupFragment.undoManager.flush()
            }
            val pos = itemCount
            configurationList[profile.id] = profile
            configurationIdList.add(profile.id)
            allProfileIds.add(profile.id)
            notifyItemInserted(pos)
        }
    }

    override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {
        if (profile.groupId != groupFragment.proxyGroup.id) return
        groupFragment.configurationListView.post {
            // compute the index here: this callback runs on a background
            // dispatcher while the main thread mutates the list
            val index = configurationIdList.indexOf(profile.id)
            if (index < 0) return@post
            if (groupFragment.isUndoManagerInitialized) {
                groupFragment.undoManager.flush()
            }
            // read before the put below: noTraffic means this update
            // carries no live counters, so the previously displayed ones
            // have to be re-posted — reading after the put would just
            // hand back the incoming profile's stale DB values
            val oldProfile = configurationList[profile.id]
            configurationList[profile.id] = profile
            notifyItemChanged(index)
            //
            if (noTraffic && oldProfile != null) {
                runOnDefaultDispatcher {
                    onUpdated(
                        TrafficData(
                            id = profile.id,
                            rx = oldProfile.rx,
                            tx = oldProfile.tx
                        )
                    )
                }
            }
        }
    }

    override suspend fun onUpdated(data: TrafficData) {
        onMainDispatcher {
            try {
                // touch the list on the main thread: this callback runs
                // on a background dispatcher while it mutates the list
                val index = configurationIdList.indexOf(data.id)
                if (index != -1) {
                    val holder = groupFragment.layoutManager.findViewByPosition(index)
                        ?.let { groupFragment.configurationListView.getChildViewHolder(it) } as ConfigurationHolder?
                    holder?.bind(holder.entity, data)
                }
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }

    override suspend fun onRemoved(groupId: Long, profileId: Long) {
        if (groupId != groupFragment.proxyGroup.id) return

        groupFragment.configurationListView.post {
            allProfileIds.remove(profileId)
            val index = configurationIdList.indexOf(profileId)
            if (index < 0) return@post
            configurationIdList.removeAt(index)
            configurationList.remove(profileId)
            notifyItemRemoved(index)
        }
    }

    override suspend fun groupAdd(group: ProxyGroup) = Unit
    override suspend fun groupRemoved(groupId: Long) = Unit

    override suspend fun groupUpdated(group: ProxyGroup) {
        if (group.id != groupFragment.proxyGroup.id) return
        groupFragment.proxyGroup = group
        reloadProfiles()
    }

    override suspend fun groupUpdated(groupId: Long) {
        if (groupId != groupFragment.proxyGroup.id) return
        // null when the group was deleted mid-update (e.g. subscription)
        groupFragment.proxyGroup = SagerDatabase.groupDao.getById(groupId) ?: return
        reloadProfiles()
    }

    fun reloadProfiles() {
        var newProfiles = SagerDatabase.proxyDao.getByGroup(groupFragment.proxyGroup.id)
        if (groupFragment.select && groupFragment.noChain) {
            // a chain cannot be a group's front/landing proxy:
            // resolveChain() adds it raw and buildChain has no ChainBean branch
            newProfiles = newProfiles.filter { it.type != ProxyEntity.TYPE_CHAIN }
        }
        when (groupFragment.proxyGroup.order) {
            GroupOrder.BY_NAME -> {
                newProfiles = newProfiles.sortedBy { it.displayName() }

            }

            GroupOrder.BY_DELAY -> {
                newProfiles =
                    newProfiles.sortedBy { if (it.status == 1) it.ping else 114514 }
            }
        }

        val newProfileIds = newProfiles.map { it.id }

        var selectedProfileIndex = -1

        if (groupFragment.selected) {
            val selectedProxy = groupFragment.selectedItem?.id ?: DataStore.selectedProxy
            selectedProfileIndex = newProfileIds.indexOf(selectedProxy)
        }

        groupFragment.configurationListView.post {
            // mutate the lists on the main thread: this runs on a
            // background dispatcher while the main thread reads them
            configurationList.clear()
            configurationList.putAll(newProfiles.associateBy { it.id })
            configurationIdList.clear()
            configurationIdList.addAll(newProfileIds)
            allProfileIds.clear()
            allProfileIds.addAll(newProfileIds)
            notifyDataSetChanged()

            if (selectedProfileIndex != -1) {
                groupFragment.configurationListView.scrollTo(selectedProfileIndex, true)
            } else if (newProfiles.isNotEmpty()) {
                groupFragment.configurationListView.scrollTo(0, true)
            }

        }
    }

}
