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
    val monto: Double,
    val nota: String = "",
)

data class PrestamoRequest(
    val socioId: String,
    val monto: Double,
    val tasa: Double,
    val numCuotas: Int,
    val fechaInicio: LocalDate,
    val aplicarGraciaDiciembre: Boolean = true,
)

data class RegenerarTablaRequest(
    val tasa: Double,
    val numCuotas: Int,
    val fechaInicio: LocalDate,
    val aplicarGraciaDiciembre: Boolean = true,
)

data class PagoCuotaRequest(
    val fechaPago: LocalDate,
    val montoPagado: Double,
)

data class GastoRequest(
    val concepto: String,
    val periodo: String,
    val fecha: LocalDate,
    val monto: Double,
)

data class LiquidarUtilidadRequest(
    val periodo: String,
)

data class ErrorResponse(val error: String)

data class ConfigRequest(
    val tasaDefault: Double,
    val ajusteBancos: Double = 0.0,
)

data class SocioSaldoResponse(
    val socioId: String,
    val nombre: String,
    val activo: Boolean,
    val aportes: Double,
    val prestamoPendiente: Double,
    val utilidadRecibida: Double,
    val saldoNeto: Double,
)

data class ResumenResponse(
    val bancos: Double,
    val aportesTotales: Double,
    val prestamosPendientes: Double,
    val utilidadSinLiquidar: Double,
    val interesesMesActual: Double,
    val saldosPorSocio: List<SocioSaldoResponse>,
)

data class UtilidadPeriodoResponse(
    val periodo: String,
    val intereses: Double,
    val gastos: Double,
    val neta: Double,
    val distribucion: List<com.perseo.rgc.model.DistribucionItem>,
    val yaLiquidado: Boolean,
)

data class ProyeccionMesResponse(
    val periodo: String,
    val interesesMes: Double,
    val utilidadMes: Double,
    val acumUtilidad: Double,
    val acumAportes: Double,
)