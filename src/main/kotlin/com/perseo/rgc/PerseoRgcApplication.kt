package com.perseo.rgc

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class PerseoRgcApplication

fun main(args: Array<String>) {
    runApplication<PerseoRgcApplication>(*args)
}
