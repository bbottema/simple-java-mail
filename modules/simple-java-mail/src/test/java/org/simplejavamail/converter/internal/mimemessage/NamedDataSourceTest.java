package org.simplejavamail.converter.internal.mimemessage;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import javax.activation.DataSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class NamedDataSourceTest {

    @Mock
    private DataSource dataSource;

    @Before
    public void setUp() throws IOException {
        when(dataSource.getInputStream()).thenReturn(new ByteArrayInputStream("".getBytes()));
        when(dataSource.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(dataSource.getContentType()).thenReturn("");
    }

    @Test
    public void renameWillWork() {
        DataSource testDataSource = new NamedDataSource("newName", dataSource);
        assertThat(testDataSource.getName()).isEqualTo("newName");
        verifyZeroInteractions(dataSource);
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