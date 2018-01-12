package org.simplejavamail.converter.internal.mimemessage;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import javax.activation.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class NamedDataSourceTest {

    @Mock
    private DataSource dataSource;

    @Before
    public void setUp() {
        when(dataSource.getName()).thenReturn("testName");
    }

    @Test
    public void renameWillWork() {
        final DataSource testDataSource = new NamedDataSource("newName", dataSource);
        assertThat(testDataSource.getName()).isEqualTo("newName");
        verifyZeroInteractions(dataSource);
    }

    @Test
    public void inputStreamWillBeTheSame1() throws Exception {
        final DataSource testDataSource = new NamedDataSource("newName", dataSource);
        testDataSource.getInputStream();
        verify(dataSource).getInputStream();
    }

    @Test
    public void outputStreamWillBeTheSame1() throws Exception {
        final DataSource testDataSource = new NamedDataSource("newName", dataSource);
        testDataSource.getOutputStream();
        verify(dataSource).getOutputStream();
    }

    @Test
    public void contentTypeStreamWillBeTheSame1() {
        final DataSource testDataSource = new NamedDataSource("newName", dataSource);
        testDataSource.getContentType();
        verify(dataSource).getContentType();
    }
}