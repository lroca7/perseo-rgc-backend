package com.perseo.rgc.config

import com.perseo.rgc.repository.ConfigRepository
import com.perseo.rgc.service.SeedService
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class StartupRunner(
    private val seedService: SeedService,
    private val configRepository: ConfigRepository,
    @Value("\${app.seed-on-empty}") private val seedOnEmpty: Boolean,
    @Value("\${app.default-tasa-interes}") private val tasaDefault: Double,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments?) {
        if (seedOnEmpty) {
            seedService.seedIfEmpty(tasaDefault)
        }
        if (configRepository.count() == 0L) {
            configRepository.save(com.perseo.rgc.model.ConfigGlobal(tasaDefault = tasaDefault))
        }
    }
}
