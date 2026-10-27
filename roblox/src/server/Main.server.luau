local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Config = require(ReplicatedStorage.Shared.Config)
local Remotes = require(script.Parent.Remotes)
local Environment = require(script.Parent.Environment)
local Zombies = require(script.Parent.Zombies)
local Weapons = require(script.Parent.Weapons)
local Powers = require(script.Parent.Powers)

local rng = Random.new()

Players.RespawnTime = Config.RespawnSeconds
Environment.setupLighting()
Environment.buildMap()
Zombies.startAI()

-- Players ---------------------------------------------------------------

local function onPlayer(player)
	local stats = Instance.new("Folder")
	stats.Name = "leaderstats"
	local kills = Instance.new("IntValue")
	kills.Name = "Kills"
	kills.Parent = stats
	stats.Parent = player
	player:SetAttribute("Ult", 50)

	player.CharacterAdded:Connect(function()
		player:WaitForChild("Backpack")
		task.wait()
		Weapons.give(player)
	end)
end
Players.PlayerAdded:Connect(onPlayer)
for _, p in Players:GetPlayers() do
	onPlayer(p)
end

Zombies.onKilled.Event:Connect(function(_, killer, typeName)
	if not killer then
		return
	end
	local stats = killer:FindFirstChild("leaderstats")
	if stats then
		stats.Kills.Value += 1
	end
	Powers.addCharge(killer, Config.Zombies[typeName].Charge)
end)

-- Waves -----------------------------------------------------------------

local function anyoneAlive()
	for _, p in Players:GetPlayers() do
		local hum = p.Character and p.Character:FindFirstChildOfClass("Humanoid")
		if hum and hum.Health > 0 then
			return true
		end
	end
	return false
end

local function setStatus(state, wave, remaining, countdown)
	ReplicatedStorage:SetAttribute("State", state)
	ReplicatedStorage:SetAttribute("Wave", wave)
	ReplicatedStorage:SetAttribute("Remaining", remaining)
	ReplicatedStorage:SetAttribute("Countdown", countdown)
end

local function buildQueue(wave)
	local queue = {}
	if wave % 5 == 0 then
		table.insert(queue, "Boss")
	end
	for _ = 1, math.min(6 + wave * 3, 60) do
		local roll = rng:NextNumber()
		if wave >= 4 and roll > 0.88 then
			table.insert(queue, "Brute")
		elseif wave >= 2 and roll < 0.25 then
			table.insert(queue, "Runner")
		else
			table.insert(queue, "Walker")
		end
	end
	return queue
end

-- Returns true if the whole squad was wiped out.
local function runWave(wave)
	local queue = buildQueue(wave)
	local index = 1
	local healthMult = 1 + 0.08 * (wave - 1)
	local deadChecks = 0
	Remotes.Announce:FireAllClients("WAVE " .. wave, #queue .. " hostiles incoming")

	while true do
		local remaining = (#queue - index + 1) + Zombies.count()
		setStatus("Wave", wave, remaining, 0)
		if remaining == 0 then
			return false
		end

		deadChecks = anyoneAlive() and 0 or deadChecks + 1
		if deadChecks >= 3 then
			Zombies.clear()
			Remotes.Announce:FireAllClients("SQUAD DOWN", "Regrouping...")
			return true
		end

		if index <= #queue and Zombies.count() < Config.MaxAlive then
			local typeName = queue[index]
			index += 1
			local model = Zombies.spawn(typeName, Environment.randomSpawn(), healthMult)
			if model and typeName == "Boss" then
				Zombies.setFrozen(model, true)
				Remotes.Cutscene:FireAllClients("BossIntro", {
					Boss = model, Title = Config.Zombies.Boss.Name, Subtitle = "WAVE " .. wave,
				})
				task.wait(4.6)
				Zombies.setFrozen(model, false)
			end
		end
		task.wait(Config.SpawnInterval)
	end
end

task.spawn(function()
	local wave = 1
	while true do
		repeat
			task.wait(1)
		until anyoneAlive()

		for t = Config.IntermissionSeconds, 1, -1 do
			setStatus("Intermission", wave, 0, t)
			task.wait(1)
		end

		if not runWave(wave) then
			Remotes.Announce:FireAllClients("WAVE CLEARED", "Squad healed, power surge!")
			for _, p in Players:GetPlayers() do
				local hum = p.Character and p.Character:FindFirstChildOfClass("Humanoid")
				if hum and hum.Health > 0 then
					hum.Health = hum.MaxHealth
				else
					p:LoadCharacter()
				end
				Powers.addCharge(p, 25)
			end
			wave += 1
		else
			-- Wait for respawns, then retry the same wave.
			task.wait(Config.RespawnSeconds + 1)
		end
	end
end)
