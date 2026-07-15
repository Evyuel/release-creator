package com.dtim.releasecreator.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProductionReleaseBuildServiceTest {

    private final ProductionReleaseBuildService service = new ProductionReleaseBuildService();

    @Test
    void usesExplicitExceptions() {
        assertThat(service.getReleaseProductionTypeForRepo("service1")).isEqualTo("ser1_ReleaseProduction");
        assertThat(service.getReleaseProductionTypeForRepo("service2")).isEqualTo("service_2_ReleaseProduction");
    }

    @Test
    void buildsDefaultIdFromPascalCaseRepositorySlug() {
        assertThat(service.getReleaseProductionTypeForRepo("order-payment_service"))
                .isEqualTo("OrderPaymentService_Deployment_ReleaseProduction");
    }
}
