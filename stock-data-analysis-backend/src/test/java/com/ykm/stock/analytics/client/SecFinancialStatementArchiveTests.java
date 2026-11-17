package com.ykm.stock.analytics.client;

import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SecFinancialStatementArchiveTests {
    private static final String QUARTER = "/files/dera/data/financial-statement-data-sets/2026q3.zip";
    private static final String INDEX = "<a href='" + QUARTER + "'>2026 Q3</a>";
    @TempDir Path directory;
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    void downloadsOnlyMissingPublishedQuarterAndReusesIt() throws IOException {
        Files.createFile(directory.resolve("2015q4.zip"));
        String index = INDEX + "<a href='/files/dera/data/financial-statement-data-sets/2015q4.zip'>2015 Q4</a>"
                + "<a href='/files/dera/data/financial-statement-data-sets/2013q4.zip'>2013 Q4</a>";
        byte[] zip = archiveBytes(List.of("sub.txt", "pre.txt", "num.txt"));
        server.expect(requestTo(SecFinancialStatementArchive.INDEX)).andRespond(withSuccess(index, MediaType.TEXT_HTML));
        server.expect(requestTo("https://www.sec.gov" + QUARTER)).andRespond(withSuccess(zip, MediaType.APPLICATION_OCTET_STREAM));
        server.expect(requestTo(SecFinancialStatementArchive.INDEX)).andRespond(withSuccess(index, MediaType.TEXT_HTML));
        SecFinancialStatementArchive archive = new SecFinancialStatementArchive(builder.build(), directory.toString());
        archive.downloadMissing(2014);
        archive.downloadMissing(2014);
        assertThat(directory.resolve("2026q3.zip")).hasBinaryContent(zip);
        assertThat(directory.resolve("2013q4.zip")).doesNotExist();
        server.verify();
    }

    @Test
    void invalidDownloadIsNeverPublished() throws IOException {
        server.expect(requestTo(SecFinancialStatementArchive.INDEX)).andRespond(withSuccess(INDEX, MediaType.TEXT_HTML));
        server.expect(requestTo("https://www.sec.gov" + QUARTER))
                .andRespond(withSuccess(archiveBytes(List.of("sub.txt")), MediaType.APPLICATION_OCTET_STREAM));
        SecFinancialStatementArchive archive = new SecFinancialStatementArchive(builder.build(), directory.toString());
        assertThatThrownBy(() -> archive.downloadMissing(2014)).hasRootCauseMessage("SEC_FINANCIAL_STATEMENT_TABLE_MISSING:pre.txt");
        assertThat(directory).isEmptyDirectory();
        server.verify();
    }

    @Test
    void recognizesPureAmpersandCashFlowLabelButNotDepletionOrAccretion() throws IOException {
        String pureAccession = "0000000001-26-000001";
        String depletionAccession = "0000000001-26-000002";
        String accretionAccession = "0000000001-26-000003";
        String sub = "adsh\tcik\tfiled\tform\tperiod\tsic\n"
                + pureAccession + "\t1\t20260813\t10-Q\t20260630\t2834\n"
                + depletionAccession + "\t1\t20260813\t10-Q\t20260630\t2834\n"
                + accretionAccession + "\t1\t20260813\t10-Q\t20260630\t2834\n";
        String pre = "adsh\treport\tline\tstmt\tinpth\trfile\ttag\tversion\tplabel\tnegating\n"
                + pureAccession + "\t1\t1\tCF\t0\tH\tDepreciationAndAmortization\tus-gaap/2026\tDepreciation & Amortization Expense\t0\n"
                + depletionAccession + "\t1\t1\tCF\t0\tH\tDepreciationDepletionAndAmortization\tus-gaap/2026\tDepreciation, depletion & amortization\t0\n"
                + accretionAccession + "\t1\t1\tCF\t0\tH\tDepreciationAmortizationAndAccretionNet\tus-gaap/2026\tDepreciation & amortization and accretion\t0\n";
        String num = "adsh\ttag\tversion\tddate\tqtrs\tuom\tcoreg\tvalue\tsegments\n"
                + pureAccession + "\tDepreciationAndAmortization\tus-gaap/2026\t20260630\t2\tUSD\t\t14866000\t\n"
                + depletionAccession + "\tDepreciationDepletionAndAmortization\tus-gaap/2026\t20260630\t2\tUSD\t\t15000000\t\n"
                + accretionAccession + "\tDepreciationAmortizationAndAccretionNet\tus-gaap/2026\t20260630\t2\tUSD\t\t16000000\t\n";
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(directory.resolve("2026q3.zip")))) {
            addTable(zip, "sub.txt", sub);
            addTable(zip, "pre.txt", pre);
            addTable(zip, "num.txt", num);
        }
        SecFinancialFact pure = depreciationFact(pureAccession, "DepreciationAndAmortization", "14866000");
        SecFinancialFact depletion = depreciationFact(depletionAccession, "DepreciationDepletionAndAmortization", "15000000");
        SecFinancialFact accretion = depreciationFact(accretionAccession, "DepreciationAmortizationAndAccretionNet", "16000000");
        SecFinancialStatementArchive archive = new SecFinancialStatementArchive(builder.build(), directory.toString());
        Set<SecFinancialFactConverter.FactKey> approved = archive.loadDepreciation(Set.of("0000000001"))
                .apply(List.of(pure, depletion, accretion));
        assertThat(approved).containsExactly(SecFinancialFactConverter.key(pure));
    }

    private void addTable(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private SecFinancialFact depreciationFact(String accession, String tag, String value) {
        SecFinancialFact fact = new SecFinancialFact();
        fact.setCik("0000000001");
        fact.setAccession(accession);
        fact.setForm("10-Q");
        fact.setFiled(LocalDate.of(2026, 8, 13));
        fact.setTaxonomy("us-gaap");
        fact.setTag(tag);
        fact.setUnit("USD");
        fact.setPeriodStart(LocalDate.of(2026, 1, 1));
        fact.setPeriodEnd(LocalDate.of(2026, 6, 30));
        fact.setValue(new BigDecimal(value));
        return fact;
    }

    private byte[] archiveBytes(List<String> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String file : files) {
                zip.putNextEntry(new ZipEntry(file));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
