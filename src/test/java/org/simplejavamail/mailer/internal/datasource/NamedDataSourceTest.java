package org.simplejavamail.mailer.internal.datasource;

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
    public void setUp() throws Exception {
        when(dataSource.getName()).thenReturn("testName");
    }

    @Test
    public void renameWillWork() throws Exception {
        DataSource testDataSource = new NamedDataSource("newName", dataSource);
        assertThat(testDataSource.getName()).isEqualTo("newName");
        verifyZeroInteractions(dataSource);
    }

    @Test
    public void originalNameWillWork() throws Exception {
        DataSource testDataSource = new NamedDataSource(dataSource);
        assertThat(testDataSource.getName()).isEqualTo("testName");
        verify(dataSource).getName();
    }

    @Test
    public void inputStreamWillBeTheSame1() throws Exception {
        DataSource testDataSource = new NamedDataSource("newName", dataSource);
        testDataSource.getInputStream();
        verify(dataSource).getInputStream();
    }

    @Test
    public void inputStreamWillBeTheSame2() throws Exception {
        DataSource testDataSource = new NamedDataSource(dataSource);
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
    public void outputStreamWillBeTheSame2() throws Exception {
        DataSource testDataSource = new NamedDataSource(dataSource);
        testDataSource.getOutputStream();
        verify(dataSource).getOutputStream();
    }

    @Test
    public void contentTypeStreamWillBeTheSame1() throws Exception {
        DataSource testDataSource = new NamedDataSource("newName", dataSource);
        testDataSource.getContentType();
        verify(dataSource).getContentType();
    }

    @Test
    public void contentTypeStreamWillBeTheSame2() throws Exception {
        DataSource testDataSource = new NamedDataSource(dataSource);
        testDataSource.getContentType();
        verify(dataSource).getContentType();
    }

}