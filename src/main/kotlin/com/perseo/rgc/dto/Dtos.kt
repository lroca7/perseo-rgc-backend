package com.perseo.rgc.dto

import java.time.LocalDate

data class SocioRequest(
    val nombre: String,
    val activo: Boolean = true,
    val fechaIngreso: LocalDate = LocalDate.now(),
)

data class AporteRequest(
    val socioId: String,
    val periodo: String,
    val fecha: LocalDate,
    val monto: Long,
    val nota: String = "",
)

data class PrestamoRequest(
    val socioId: String,
    val monto: Long,
    val tasa: Double,
    val numCuotas: Int,
    val fechaInicio: LocalDate,
)

data class RegenerarTablaRequest(
    val tasa: Double,
    val numCuotas: Int,
    val fechaInicio: LocalDate,
)

data class PagoCuotaRequest(
    val fechaPago: LocalDate,
    val montoPagado: Long,
)

data class GastoRequest(
    val concepto: String,
    val periodo: String,
    val fecha: LocalDate,
    val monto: Long,
)

data class LiquidarUtilidadRequest(
    val periodo: String,
)

data class ConfigRequest(
    val tasaDefault: Double,
)

data class SocioSaldoResponse(
    val socioId: String,
    val nombre: String,
    val activo: Boolean,
    val aportes: Long,
    val prestamoPendiente: Long,
    val utilidadRecibida: Long,
    val saldoNeto: Long,
)

data class ResumenResponse(
    val bancos: Long,
    val aportesTotales: Long,
    val prestamosPendientes: Long,
    val utilidadSinLiquidar: Long,
    val interesesMesActual: Long,
    val saldosPorSocio: List<SocioSaldoResponse>,
)

data class UtilidadPeriodoResponse(
    val periodo: String,
    val intereses: Long,
    val gastos: Long,
    val neta: Long,
    val distribucion: List<com.perseo.rgc.model.DistribucionItem>,
    val yaLiquidado: Boolean,
)

data class ProyeccionMesResponse(
    val periodo: String,
    val interesesMes: Long,
    val utilidadMes: Long,
    val acumUtilidad: Long,
    val acumAportes: Long,
)
