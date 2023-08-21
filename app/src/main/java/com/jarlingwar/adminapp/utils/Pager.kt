package com.jarlingwar.adminapp.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

abstract class AbstractPager<T> {
    abstract fun onError(t: Throwable)
    abstract fun onSuccess(result: List<T?>)
    abstract fun onLoadNext()
    open fun onFirstLoad() { }
    abstract fun onNoResults()
}

class Pager<T>(
    private val scope: CoroutineScope,
    private val pager: AbstractPager<T>,
    private val pagingFlow: (Flow<Int>) -> Flow<List<T?>>
    ) {
    private val pagingReference = MutableStateFlow(0)
    private var pagingJob: Job? = null
    private var totalPaged = 0

    fun reload() {
        pager.onFirstLoad()
        if (pagingJob != null) {
            pagingJob?.cancel()
            pagingJob = null
        }
        pagingJob = scope.launch(Dispatchers.IO) {
            pagingFlow.invoke(pagingReference)
                .catch { pager.onError(it) }
                .collect {
                  if (it.isEmpty()) {
                      pager.onNoResults()
                  } else {
                      totalPaged = it.size
                      pager.onSuccess(it)
                  }
            }
        }
    }

    fun loadNext() {
        pager.onLoadNext()
        scope.launch { pagingReference.update { totalPaged } }
    }
}