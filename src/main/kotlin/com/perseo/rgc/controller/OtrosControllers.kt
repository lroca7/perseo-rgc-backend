package com.perseo.rgc.controller

import com.perseo.rgc.dto.*
import com.perseo.rgc.model.ConfigGlobal
import com.perseo.rgc.model.Gasto
import com.perseo.rgc.model.Utilidad
import com.perseo.rgc.repository.ConfigRepository
import com.perseo.rgc.repository.GastoRepository
import com.perseo.rgc.repository.UtilidadRepository
import com.perseo.rgc.service.SaldosService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/gastos")
class GastoController(private val gastoRepository: GastoRepository) {
    @GetMapping
    fun listar(): List<Gasto> = gastoRepository.findAll()

    @PostMapping
    fun crear(@RequestBody req: GastoRequest): Gasto =
        gastoRepository.save(Gasto(concepto = req.concepto, periodo = req.periodo, fecha = req.fecha, monto = req.monto))

    @DeleteMapping("/{id}")
    fun eliminar(@PathVariable id: String): ResponseEntity<Void> {
        gastoRepository.deleteById(id)
        return ResponseEntity.noContent().build()
    }
}

@RestController
@RequestMapping("/api/config")
class ConfigController(private val configRepository: ConfigRepository) {
    @GetMapping
    fun obtener(): ConfigGlobal = configRepository.findById("global").orElse(ConfigGlobal())

    @PutMapping
    fun actualizar(@RequestBody req: ConfigRequest): ConfigGlobal {
        val actual = configRepository.findById("global").orElse(ConfigGlobal())
        actual.tasaDefault = req.tasaDefault
        actual.ajusteBancos = req.ajusteBancos
        return configRepository.save(actual)
    }
}

@RestController
@RequestMapping("/api/resumen")
class ResumenController(private val saldosService: SaldosService) {
    @GetMapping
    fun resumen(): ResumenResponse = saldosService.resumen()
}

@RestController
@RequestMapping("/api/utilidades")
class UtilidadController(
    private val saldosService: SaldosService,
    private val utilidadRepository: UtilidadRepository,
) {
    @GetMapping("/propuesta")
    fun propuesta(@RequestParam periodo: String): UtilidadPeriodoResponse = saldosService.proponerDistribucion(periodo)

    @GetMapping
    fun historial(): List<Utilidad> = utilidadRepository.findAll().sortedByDescending { it.periodo }

    @PostMapping("/liquidar")
    fun liquidar(@RequestBody req: LiquidarUtilidadRequest): ResponseEntity<Utilidad> {
        if (utilidadRepository.findByPeriodo(req.periodo) != null) {
            return ResponseEntity.status(409).build()
        }
        val propuesta = saldosService.proponerDistribucion(req.periodo)
        val utilidad = Utilidad(
            periodo = req.periodo, totalIntereses = propuesta.intereses, totalGastos = propuesta.gastos,
            utilidadNeta = propuesta.neta, distribucion = propuesta.distribucion,
        )
        return ResponseEntity.ok(utilidadRepository.save(utilidad))
    }
}

@RestController
@RequestMapping("/api/proyeccion")
class ProyeccionController(private val saldosService: SaldosService) {
    @GetMapping
    fun proyectar(
        @RequestParam(defaultValue = "6") meses: Int,
        @RequestParam(defaultValue = "0") aporteExtra: Double,
    ): List<ProyeccionMesResponse> = saldosService.proyectar(meses, aporteExtra)
}