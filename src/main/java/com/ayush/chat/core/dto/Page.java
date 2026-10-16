package com.ayush.chat.core.dto;

import java.util.List;

/** A page of results with the total count, for offset-paged listings. */
public record Page<T>(List<T> items, long total) {
}
