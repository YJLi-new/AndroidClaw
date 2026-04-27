package ai.androidclaw.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

internal fun <VM : ViewModel> viewModelFactory(create: () -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val viewModel = create()
            require(modelClass.isAssignableFrom(viewModel::class.java)) {
                "Factory created ${viewModel::class.java.name}, but ${modelClass.name} was requested."
            }
            return viewModel as T
        }
    }
