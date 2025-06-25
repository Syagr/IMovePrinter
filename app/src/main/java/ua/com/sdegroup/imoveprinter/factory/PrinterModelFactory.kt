package ua.com.sdegroup.imoveprinter.factory

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import ua.com.sdegroup.imoveprinter.model.PrinterModel
class PrinterModelFactory(
  private val savedStateHandle: SavedStateHandle
) : ViewModelProvider.Factory {
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(PrinterModel::class.java)) {
      @Suppress("UNCHECKED_CAST")
      return PrinterModel(savedStateHandle) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class")
  }
}