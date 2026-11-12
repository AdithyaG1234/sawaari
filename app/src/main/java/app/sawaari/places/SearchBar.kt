package app.sawaari.places

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.sawaari.geo.GeoPoint
import kotlinx.coroutines.delay

/**
 * Search box on the map: type a town, restaurant or landmark and pick a result, or paste a
 * Google Maps / Sawaari link to load it as a route.
 */
@Composable
fun MapSearchBar(
    search: PlaceSearch,
    near: () -> GeoPoint?,
    onPick: (SearchResult) -> Unit,
    onLink: (String) -> Unit,
    onOpenLinkSettings: () -> Unit,
    modifier: Modifier = Modifier,
    prefill: String? = null,
    onPrefillUsed: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val isLink = query.contains("http") || query.startsWith("sawaari://")
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(prefill) {
        if (prefill != null) { query = prefill; runCatching { focusRequester.requestFocus() }; onPrefillUsed() }
    }

    LaunchedEffect(query) {
        searched = false
        if (isLink || query.trim().length < 2) { results = emptyList(); loading = false; return@LaunchedEffect }
        delay(450) // wait until the rider stops typing
        loading = true
        results = search.search(query, near())
        loading = false
        searched = true
    }

    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search / Paste Google Maps link", maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = ""; focus.clearFocus() }) { Icon(Icons.Default.Close, "Clear") }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (isLink) { onLink(query); query = "" }; focus.clearFocus() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { focused = it.isFocused },
            )
            if (focused && query.isEmpty()) {
                HorizontalDivider()
                Text(
                    "Tip: in Google Maps or WhatsApp, tap Share and pick Sawaari to open a route here.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
                androidx.compose.material3.TextButton(onClick = onOpenLinkSettings, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Always open map links in Sawaari")
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!focused) Unit
            else if (isLink) {
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Open this link as a route") },
                    leadingContent = { Icon(Icons.Default.Link, null) },
                    modifier = Modifier.clickable { onLink(query); query = ""; focus.clearFocus() },
                )
            } else if (results.isNotEmpty()) {
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(results) { r ->
                        ListItem(
                            headlineContent = { Text(r.name) },
                            supportingContent = {
                                // Show how far each result is, so a same-named place in another town stands out.
                                val away = near()?.let { app.sawaari.nav.formatDistance(app.sawaari.geo.distanceMeters(it, r.point)) + " away" }
                                val line = listOfNotNull(r.detail.ifBlank { null }, away).joinToString(" · ")
                                if (line.isNotBlank()) Text(line)
                            },
                            modifier = Modifier.clickable { onPick(r); query = ""; results = emptyList(); focus.clearFocus() },
                        )
                    }
                }
            } else if (searched && !loading) {
                Text(
                    "Nothing found. Offline, search covers towns and places in downloaded ride areas.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
