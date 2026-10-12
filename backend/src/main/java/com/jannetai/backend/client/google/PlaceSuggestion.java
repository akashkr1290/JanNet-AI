package com.jannetai.backend.client.google;

/**
 * One "search as you type" suggestion (Places API (New) autocomplete).
 * {@code mainText} is the place itself ("Republic Crossing"), {@code
 * secondaryText} where it is ("Raj Nagar Extension, Ghaziabad, Uttar
 * Pradesh"). The app shows both and sends {@code placeId} back to
 * GET /api/v1/geo/places/{placeId} with the same session token.
 */
public record PlaceSuggestion(String placeId, String mainText, String secondaryText) {
}
