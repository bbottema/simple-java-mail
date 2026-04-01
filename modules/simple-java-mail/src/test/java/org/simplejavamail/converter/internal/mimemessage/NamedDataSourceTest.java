package org.simplejavamail.converter.internal.mimemessage;

import jakarta.activation.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplejavamail.internal.util.NamedDataSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class NamedDataSourceTest {

    @Mock
    private DataSource dataSource;

    @BeforeEach
    public void setUp() throws IOException {
        lenient().when(dataSource.getInputStream()).thenReturn(new ByteArrayInputStream("".getBytes()));
        lenient().when(dataSource.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        lenient().when(dataSource.getContentType()).thenReturn("");
    }

    @Test
    public void renameWillWork() {
        DataSource testDataSource = new NamedDataSource("newName", dataSource);
        assertThat(testDataSource.getName()).isEqualTo("newName");
        Mockito.verifyNoInteractions(dataSource);
    }

    @Test
    public void inputStreamWillBeTheSame1() throws Exception {
        DataSource testDataSource = new NamedDataSource("newName", dataSource);
        testDataSource.getInputStream();
        verify(dataSource).getInputStream();
    }

    @Test
    public void outputStreamWillBeTheSame1() throws Exception {
        DataSource testDataSource = new NamedDataSource("newName", dataSource);
        testDataSource.getOutputStream();
        verify(dataSource).getOutputStream();
    }

    @Test
    public void contentTypeStreamWillBeTheSame1() {
        DataSource testDataSource = new NamedDataSource("newName", dataSource);
        testDataSource.getContentType();
        verify(dataSource).getContentType();
    }
}