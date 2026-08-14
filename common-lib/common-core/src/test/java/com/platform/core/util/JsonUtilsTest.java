package com.platform.core.util;

import com.platform.core.exception.BizException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonUtilsTest {

    static class Sample {
        public String name;
        public int count;
        public LocalDateTime time;
    }

    @Test
    void toJson_serializesObject() {
        String json = JsonUtils.toJson(Map.of("name", "test", "count", 3));
        assertThat(json).contains("\"name\":\"test\"");
        assertThat(json).contains("\"count\":3");
    }

    @Test
    void fromJson_deserializesObject() {
        Sample s = JsonUtils.fromJson("{\"name\":\"abc\",\"count\":5}", Sample.class);
        assertThat(s.name).isEqualTo("abc");
        assertThat(s.count).isEqualTo(5);
    }

    @Test
    void fromJson_unknownFieldsIgnored() {
        Sample s = JsonUtils.fromJson("{\"name\":\"x\",\"count\":1,\"extra\":\"ignored\"}", Sample.class);
        assertThat(s.name).isEqualTo("x");
        assertThat(s.count).isEqualTo(1);
    }

    @Test
    void localDateTime_roundTrip_isoFormat() {
        LocalDateTime time = LocalDateTime.of(2026, 8, 14, 12, 30, 45);
        String json = JsonUtils.toJson(Map.of("time", time));
        assertThat(json).contains("2026-08-14T12:30:45");
    }

    @Test
    void toJson_null_returnsNullLiteral() {
        assertThat(JsonUtils.toJson(null)).isEqualTo("null");
    }

    @Test
    void fromJson_invalidJson_throwsBizException() {
        assertThatThrownBy(() -> JsonUtils.fromJson("not-json", Sample.class))
                .isInstanceOf(BizException.class);
    }

    @Test
    void fromJson_list() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = JsonUtils.fromJson("[{\"a\":1},{\"a\":2}]", List.class);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("a")).isEqualTo(1);
    }
}
