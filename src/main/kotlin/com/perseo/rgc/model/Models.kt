package com.perseo.rgc.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDate

@Document(collection = "socios")
data class Socio(
    @Id val id: String? = null,
    var nombre: String,
    var activo: Boolean = true,
    var fechaIngreso: LocalDate = LocalDate.now(),
)

@Document(collection = "aportes")
data class Aporte(
    @Id val id: String? = null,
    @Indexed
    var socioId: String,
    /** Formato "YYYY-MM" */
    var periodo: String,
    var fecha: LocalDate,
    var monto: Long,
    var nota: String = "",
)

data class Cuota(
    var numero: Int,
    var fechaProgramada: LocalDate,
    var cuota: Long,
    var interes: Long,
    var capital: Long,
    var saldo: Long,
    var pagado: Boolean = false,
    var fechaPago: LocalDate? = null,
    var montoPagado: Long? = null,
    var esGracia: Boolean = false,
)

@Document(collection = "prestamos")
data class Prestamo(
    @Id val id: String? = null,
    @Indexed
    var socioId: String,
    var monto: Long,
    /** Tasa mensual como fracción, ej 0.05 = 5% */
    var tasa: Double,
    var numCuotas: Int,
    var fechaInicio: LocalDate,
    var estado: String = "activo", // activo | pagado
    var cuotas: MutableList<Cuota> = mutableListOf(),
    var nota: String = "",
)

@Document(collection = "gastos")
data class Gasto(
    @Id val id: String? = null,
    var concepto: String,
    var periodo: String,
    var fecha: LocalDate,
    var monto: Long,
)

data class DistribucionItem(
    var socioId: String,
    var aportesBase: Long,
    var monto: Long,
)

@Document(collection = "utilidades")
data class Utilidad(
    @Id val id: String? = null,
    /** Formato "YYYY-MM" */
    var periodo: String,
    var totalIntereses: Long,
    var totalGastos: Long,
    var utilidadNeta: Long,
    var distribucion: List<DistribucionItem> = emptyList(),
    var fecha: LocalDate = LocalDate.now(),
)

@Document(collection = "config")
data class ConfigGlobal(
    @Id val id: String = "global",
    var tasaDefault: Double = 0.05,
    var ultimaLiquidacion: String? = null,
)