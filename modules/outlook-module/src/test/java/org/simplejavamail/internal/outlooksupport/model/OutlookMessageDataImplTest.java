package org.simplejavamail.internal.outlooksupport.model;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.outlook.OutlookMessageData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutlookMessageDataImplTest {
    @Test
    void constructionAndSerializationPreserveDefensiveCopies() throws Exception {
        final Date date = new Date(1234);
        final List<String> headerValues = new ArrayList<>(List.of("original"));
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("X-Source", headerValues);
        final Set<Integer> propertyCodes = new LinkedHashSet<>(Set.of(42));
        final OutlookMessageData data = OutlookMessageDataImpl.builder()
            .headers(headers)
            .date(date)
            .clientSubmitTime(date)
            .creationDate(date)
            .lastModificationDate(date)
            .propertyCodes(propertyCodes)
            .build();

        date.setTime(99);
        headerValues.clear();
        headers.clear();
        propertyCodes.clear();

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(data);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            final OutlookMessageData restored = (OutlookMessageData) input.readObject();
            for (final OutlookMessageData snapshot : List.of(data, restored)) {
                snapshot.getDate().setTime(99);
                snapshot.getClientSubmitTime().setTime(99);
                snapshot.getCreationDate().setTime(99);
                snapshot.getLastModificationDate().setTime(99);
                assertThat(snapshot.getDate()).isEqualTo(new Date(1234));
                assertThat(snapshot.getClientSubmitTime()).isEqualTo(new Date(1234));
                assertThat(snapshot.getCreationDate()).isEqualTo(new Date(1234));
                assertThat(snapshot.getLastModificationDate()).isEqualTo(new Date(1234));
                assertThat(snapshot.getHeaderValues("x-source")).containsExactly("original");
                assertThat(snapshot.getPropertyCodes()).containsExactly(42);
                assertThatThrownBy(() -> snapshot.getHeaders().clear()).isInstanceOf(UnsupportedOperationException.class);
                assertThatThrownBy(() -> snapshot.getHeaders().get("X-Source").clear()).isInstanceOf(UnsupportedOperationException.class);
                assertThatThrownBy(() -> snapshot.getPropertyCodes().clear()).isInstanceOf(UnsupportedOperationException.class);
            }
        }
    }
}
