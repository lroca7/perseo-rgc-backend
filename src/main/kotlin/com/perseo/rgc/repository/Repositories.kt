package com.perseo.rgc.repository

import com.perseo.rgc.model.*
import org.springframework.data.mongodb.repository.MongoRepository

interface SocioRepository : MongoRepository<Socio, String>

interface AporteRepository : MongoRepository<Aporte, String> {
    fun findBySocioId(socioId: String): List<Aporte>
}

interface PrestamoRepository : MongoRepository<Prestamo, String> {
    fun findBySocioId(socioId: String): List<Prestamo>
}

interface GastoRepository : MongoRepository<Gasto, String> {
    fun findByPeriodo(periodo: String): List<Gasto>
}

interface UtilidadRepository : MongoRepository<Utilidad, String> {
    fun findByPeriodo(periodo: String): Utilidad?
}

interface ConfigRepository : MongoRepository<ConfigGlobal, String>
