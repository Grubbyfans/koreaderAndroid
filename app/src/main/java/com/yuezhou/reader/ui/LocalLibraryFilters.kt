package com.yuezhou.reader.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.reader.LibraryStore
import com.yuezhou.reader.YuezhouColors
import com.yuezhou.reader.YuezhouDimens
import com.yuezhou.reader.ui.navigation.SwipeBlockRegistry

internal enum class ReadingFilter(val label: String) {
    ALL("全部"), UNREAD("未读"), READING("在读"), FINISHED("已读");

    fun matches(book: LibraryStore.Entry): Boolean = when (this) {
        ALL -> true
        UNREAD -> book.location == null && book.lastReadAt == null && (book.percent ?: 0.0) <= 0.0
        READING -> !UNREAD.matches(book) && !FINISHED.matches(book)
        FINISHED -> (book.percent ?: 0.0) >= 1.0
    }
}

internal enum class LibrarySort(val label: String) {
    RECENT("最近阅读"), TITLE("按书名"),
}

internal fun localLibraryResults(
    books: List<LibraryStore.Entry>,
    query: String,
    filter: ReadingFilter,
    sort: LibrarySort,
    authors: Map<String, List<String>>,
): List<LibraryStore.Entry> {
    val matches = books.filter { book ->
        filter.matches(book) && (query.isEmpty() || book.title.contains(query, ignoreCase = true) ||
            book.fileName.contains(query, ignoreCase = true) ||
            authors[book.bookId].orEmpty().any { it.contains(query, ignoreCase = true) })
    }
    return when (sort) {
        LibrarySort.RECENT -> matches.sortedByDescending { it.lastReadAt ?: Long.MIN_VALUE }
        LibrarySort.TITLE -> matches.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }
}

@Composable
internal fun LocalLibraryFilters(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: ReadingFilter,
    onFilterChange: (ReadingFilter) -> Unit,
    sort: LibrarySort,
    onSortChange: (LibrarySort) -> Unit,
    swipeBlocker: SwipeBlockRegistry,
) {
    val focusManager = LocalFocusManager.current
    var sortMenuOpen by remember { mutableStateOf(false) }
    DisposableEffect(swipeBlocker) {
        onDispose { swipeBlocker.release("local:search") }
    }
    Column(
        modifier = Modifier.padding(horizontal = YuezhouDimens.PageInset),
        verticalArrangement = Arrangement.spacedBy(YuezhouDimens.GapSmall),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().onFocusChanged {
                if (it.isFocused) swipeBlocker.hold("local:search")
                else swipeBlocker.release("local:search")
            },
            placeholder = { Text("搜索书名或作者") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "清空搜索")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(YuezhouDimens.CardRadius),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = YuezhouColors.Surface,
                unfocusedContainerColor = YuezhouColors.Surface,
                unfocusedBorderColor = YuezhouColors.Divider,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(YuezhouDimens.Gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReadingFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { onFilterChange(option) },
                    label = { Text(option.label) },
                    shape = RoundedCornerShape(50),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = YuezhouColors.BrandContainer,
                        selectedLabelColor = YuezhouColors.BrandDark,
                    ),
                )
            }
            Box {
                TextButton(onClick = { sortMenuOpen = true }) { Text(sort.label) }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    LibrarySort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                onSortChange(option)
                                sortMenuOpen = false
                            },
                        )
                    }
                }
            }
        }
    }
}
