# Distributed Game of Life
Created as Master Thesis in Ternopil Ivan Puluj National Technical University. Master Thesis presentation in Ukrainian: [PDF](/docs/Master%20Thesis%20Presentation%20(UK).pdf).
## Project importing
```sh
git clone git@github.com:valerii540/Distributed-Game-of-Life.git && cd Distributed-Game-of-Life
```
```sh
mill mill.bsp.BSP/install
```
Then open project in your IDE.

## Running
### As separate processes on single machine
You can use predefined bash scripts from the **scripts** folder
- `./scripts/run_master.sh` - will start master node. Master node always use **2551** and **8080** port
- `./scripts/run_worker.sh 2552 4G worker-0` - will start worker node with user-defined parameters: artery port, max memory and unique ID.
You can set additional parameter to override max field dimensions - **height \* width** (useful for debugging)

## Desktop client
There is GUI client to control cluster. It supports all available cluster API: assignments control, cluster control.
Also, it provides cluster visual overview and monitoring.
- Desktop client: https://github.com/valerii540/Distributed-Game-of-Life-Client